package it.unitn.ds2.gsfd.actors;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.event.DiagnosticLoggingAdapter;
import akka.event.Logging;
import akka.japi.Creator;
import it.unitn.ds2.gsfd.messages.Registration;
import it.unitn.ds2.gsfd.messages.ReportCrash;
import it.unitn.ds2.gsfd.messages.Start;
import it.unitn.ds2.gsfd.messages.Stop;
import it.unitn.ds2.gsfd.protocol.*;
import it.unitn.ds2.gsfd.utils.NodeInfo;
import it.unitn.ds2.gsfd.utils.NodeMap;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Akka BaseActor that implements the node's behaviour.
 */
public final class NodeActor extends AbstractActor implements BaseActor {

	/**
	 * Initialize a new node which will register itself to the Tracker node.
	 *
	 * @param trackerAddress Akka address of the tracker to contact.
	 * @return Props.
	 */
	public static Props init(final String trackerAddress) {
		return Props.create(new Creator<NodeActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public NodeActor create() {
				return new NodeActor(trackerAddress);
			}
		});
	}

	// address of the Tracker
	private final String trackerAddress;

	// with the newest Akka version actors are state machines...
	// if ready, accepts messages other than Start and Stop
	private Receive ready;
	private Receive notReady;

	// timeout to simulate crash; cancelled by Stop message.
	private Cancellable selfCrashTimeout;

	// time without heartbeat update to consider a node failed
	private long failTime;

	// time to wait before removing a failed node from beats
	private long cleanupTime;

	// nodes information (includes heartbeat counter, window, and fail control);
	// extends HashMap<ActorRef, NodeInfo>
	private NodeMap nodes;

	// timeout to issue another Gossip
	private long gossipTime;
	private Cancellable gossipTimeout;

	// parameter used to decide if the node will multicast
	private double multicastParam;

	// number of times multicast was postponed
	private long multicastWait;

	// parameter used to decide the maximum number of times multicast can be postponed
	private long multicastMaxWait;

	// timeout to issue another try for multicast
	private Cancellable multicastTimeout;

	// if true, the node replies to the gossip sender
	private boolean pullByGossip;

	// log, used for debug proposes
	private final DiagnosticLoggingAdapter log;

	/**
	 * Create a new node BaseActor. The created node will
	 * register itself on the Tracker and wait for instructions.
	 *
	 * @param trackerAddress Akka address of the tracker.
	 */
	private NodeActor(String trackerAddress) {

		// initialize values
		this.trackerAddress = trackerAddress;

		// extract my identifier
		final String id = idFromRef(getSelf());

		// setup log context
		final Map<String, Object> mdc = new HashMap<String, Object>() {{
			put("actor", "Node [" + id + "]:");
		}};
		this.log = Logging.getLogger(this);
		this.log.setMDC(mdc);

		// set ready and notReady behavior
		ready = receiveBuilder()
			.match(Start.class, this::onStart)
			.match(Stop.class, msg -> onStop())
			.match(SelfCrash.class, msg -> onCrash())
			.match(GossipReminder.class, msg -> sendGossip())
			.match(Gossip.class, this::onGossip) // TODO: sliding window
			.match(GossipReply.class, this::onGossipReply)
			.match(Fail.class, this::onFail)
			.match(Cleanup.class, this::onCleanup)
			.match(CatastropheReminder.class, msg -> sendMulticast())
			.match(CatastropheMulticast.class, this::onMulticast)
			.matchAny(msg -> log.warning("Received unknown message -> " + msg))
			.build();
		notReady = receiveBuilder()
			.match(Start.class, this::onStart)
			.match(Stop.class, msg -> onStop())
			.matchAny(msg -> log.warning("Dropped message -> " + msg))
			.build();

		getContext().become(notReady);
	}

	@Override
	public void preStart() throws Exception {
		super.preStart();
		log.info("Start... register on the Tracker");
		sendToTracker(new Registration());
	}

	/**
	 * For each type of message, call the relative callback
	 * to keep this method short and clean.
	 */
	@Override
	public Receive createReceive() {
		return receiveBuilder()
			.match(Start.class, msg -> getContext().become(ready))
			.match(Stop.class, msg -> getContext().become(notReady))
			.match(SelfCrash.class, msg -> getContext().become(notReady))
			.build();
	}

	private void onStart(Start msg) {
		log.warning("onStart start");

		getContext().become(ready);

		// set the gossip strategy
		pullByGossip = msg.isPullByGossip();

		// schedule the crash if needed
		final Long delta = msg.getDelta();
		if (delta != null) {
			selfCrashTimeout = sendToSelf(new SelfCrash(), delta);
		}

		// set times for timeouts
		gossipTime = msg.getGossipTime();
		failTime = msg.getFailTime();
		cleanupTime = 2 * failTime;

		// set the structures for nodes, and start timeouts
		nodes = new NodeMap(msg.getNodes(), getSelf());
		msg.getNodes().forEach(ref -> {
			if (ref != getSelf()) {
				// schedule to self to catch failure of the node
				Cancellable failTimeout = sendToSelf(new Fail(ref, 0), failTime);
				nodes.put(ref, new NodeInfo(failTimeout)); // TODO: #you-know-it
			}
		});

		// schedule reminder to perform first Gossip
		gossipTimeout = sendToSelf(new GossipReminder(), gossipTime);

		// setup for catastrophe recovery
		multicastParam = msg.getMulticastParam();
		multicastMaxWait = msg.getMulticastMaxWait();
		multicastWait = 0;

		// schedule reminder to attempt multicast
		multicastTimeout = sendToSelf(new CatastropheReminder(), 1000);

		// debug
		if (delta != null) log.info("onStart complete (faulty, crashes in " + msg.getDelta() + ")");
		else log.info("onStart complete (correct)");
		log.debug("nodes: " + nodes.beatsToString());
	}

	private void onStop() {
		getContext().become(notReady);
		if (selfCrashTimeout != null) selfCrashTimeout.cancel();
		if (gossipTimeout != null) gossipTimeout.cancel();
		if (multicastTimeout != null) multicastTimeout.cancel();
		nodes.clear();
		log.info("onStop complete");
	}

	private void onCrash() {
		getContext().become(notReady);
		log.info("onCrash complete");
	}

	private void sendGossip() {

		// increment node's own heartbeat counter
		nodes.get(getSelf()).heartbeat();

		// TODO: check that this works with the experiments
		// pick random correct node
		if (!nodes.getCorrectNodes().isEmpty()) {

			ActorRef gossipNode = nodes.pickNode();
			if (gossipNode != null) {
				// gossip the beats to the random node
				gossipNode.tell(new Gossip(nodes.getBeats()), getSelf());
				// lower the probability of gossiping the same node soon
				nodes.get(gossipNode).resetQuiescence();
				log.debug("gossiped to {}: " + nodes.beatsToString(), idFromRef(gossipNode));
			}

			// schedule a new reminder to Gossip
			gossipTimeout = sendToSelf(new GossipReminder(), gossipTime);
		} else {
			log.info("Gossip stopped (no correct node to gossip)");
		}
	}

	private void onGossip(Gossip msg) {
		log.debug("gossiped by {}, beats={}, push_pull={}", idFromRef(getSender()), nodes.beatsToString(), pullByGossip);

		// this method update both the beats for all nodes and resets the quiescence for the sender
		updateBeats(msg.getBeats());

		if (pullByGossip) {

			// gossip back (pull strategy)
			reply(new GossipReply(nodes.getBeats()));
		}
	}

	private void onGossipReply(GossipReply msg) {
		updateBeats(msg.getBeats());
		log.debug("gossiped (reply) by {}", idFromRef(getSender()));
	}

	/**
	 * This method is called when the failure detector detects
	 * that a node is crashed. This is a self-message.
	 *
	 * @param msg Details with the node that is though to be crashed.
	 */
	private void onFail(Fail msg) {
		ActorRef failing = msg.getFailing();
		int failId = msg.getFailId();
		// check if the Fail message was still valid
		if (nodes.get(failing).getFailId() == failId) {
			// remove from correct nodes and report to Tracker
			nodes.setFailed(failing);
			sendToTracker(new ReportCrash(failing));
			log.info("Node {} reported as failed", idFromRef(failing));
		}
		// schedule message to remove the node from the heartbeat structure
		sendToSelf(new Cleanup(failing), cleanupTime);
	}

	private void onCleanup(Cleanup msg) {
		ActorRef failed = msg.getFailed();
		nodes.remove(failed);
		log.info("Node {} cleanup", idFromRef(failed));
	}

	private void sendMulticast() {
		// evaluate probability of sending (send for sure if Wait = MaxWait)
		double multicastProb = Math.pow((double) multicastWait / multicastMaxWait, multicastParam);
		double rand = Math.random();

		if (rand < multicastProb) {
			// do multicast
			nodes.get(getSelf()).heartbeat();
			multicastWait = 0;
			multicast(new CatastropheMulticast(nodes.getBeats()));

			// TODO: check that this works with the experiments
			// even the probability of gossip to any node
			nodes.getCorrectNodes().forEach(ref -> nodes.get(ref).resetQuiescence());
			log.debug("multicast: " + nodes.beatsToString());
		} else {
			// multicast postponed
			multicastWait++;
			sendToSelf(new CatastropheReminder(), 1000);
		}
	}

	/**
	 * This method is called when this node receive a message that was
	 * sent in multicast to all nodes.
	 *
	 * @param msg Message.
	 */
	private void onMulticast(CatastropheMulticast msg) {
		multicastWait = 0;
		updateBeats(msg.getBeats());
	}

	private void updateBeats(Map<ActorRef, Long> gossipedBeats) {
		nodes.getCorrectNodes().forEach(ref -> {
			long gossipedBeatCount = gossipedBeats.get(ref);
			// if a higher heartbeat counter was gossiped, update it
			if (gossipedBeatCount > nodes.get(ref).getBeatCount()) {
				nodes.get(ref).setBeatCount(gossipedBeatCount);
				// lower the probability of gossiping the same node soon
				nodes.get(ref).resetQuiescence();
				// restart the timeout
				Fail failMsg = new Fail(ref, nodes.get(ref).getFailId() + 1);
				nodes.get(ref).resetTimeout(sendToSelf(failMsg, failTime));
			} else {
				// no heartbeat update (will increase probability of gossip)
				nodes.get(ref).quiescent();
			}
		});
	}

	/**
	 * Send a message to the tracker.
	 *
	 * @param message Message to send.
	 */
	private void sendToTracker(Serializable message) {
		getContext().actorSelection(trackerAddress).tell(message, getSelf());
	}

	/**
	 * Send a message to self.
	 *
	 * @param message Message to send.
	 * @param delay   Schedule to be sent after delay milliseconds.
	 * @return Cancellable timeout.
	 */
	private Cancellable sendToSelf(Serializable message, long delay) {
		return getContext().system().scheduler().scheduleOnce(
			Duration.create(delay, TimeUnit.MILLISECONDS),
			getSelf(),
			message,
			getContext().system().dispatcher(),
			getSelf()
		);
	}

	/**
	 * Reply to the actor that sent the last message.
	 *
	 * @param message Message to sent back.
	 */
	private void reply(Serializable message) {
		getSender().tell(message, getSelf());
	}

	/**
	 * Send the given message to all the other nodes.
	 *
	 * @param message Message to send in multicast.
	 */
	private void multicast(Serializable message) {
		nodes.getCorrectNodes().forEach(ref -> ref.tell(message, getSelf()));
	}
}
