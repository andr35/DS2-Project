package it.unitn.ds2.gsfd.actors;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.event.DiagnosticLoggingAdapter;
import akka.event.Logging;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import it.unitn.ds2.gsfd.messages.Registration;
import it.unitn.ds2.gsfd.messages.ReportCrash;
import it.unitn.ds2.gsfd.messages.Start;
import it.unitn.ds2.gsfd.messages.Stop;
import it.unitn.ds2.gsfd.protocol.*;
import it.unitn.ds2.gsfd.utils.NodeInfo;
import it.unitn.ds2.gsfd.utils.NodeMap;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;



/**
 * Akka MyActor that implements the node's behaviour.
 */
public final class NodeActor extends AbstractActor implements MyActor {

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

	// array to select random ActorRef; contains only nodes considered correct
	private List<ActorRef> correctNodes;

	// timeout to issue another Gossip
	private long gossipTime;
	private Cancellable gossipTimeout;

	// parameter used to decide if the node will multicast
	private double multicastParam;

	// number of times multicast was postponed
	private int multicastWait;

	// parameter used to decide the maximum number of times multicast can be postponed
	private int multicastMaxWait;

	// timeout to issue another try for multicast
	private Cancellable multicastTimeout;

	// log, used for debug proposes
	private final DiagnosticLoggingAdapter log;

	/**
	 * Create a new node MyActor. The created node will
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
		getContext().become(ready);
		if (msg.isFaulty()) {
			// schedule the self crash
			selfCrashTimeout = sendToSelf(new SelfCrash(), msg.getDelta());
		}
		else {
			selfCrashTimeout = null;
		}
		// set times for timeouts
		gossipTime = msg.getGossipTime();
		failTime = msg.getFailTime();
		cleanupTime = 2*failTime;
		// set the structures for nodes, and start timeouts
		correctNodes = new ArrayList<>(msg.getNodes());
		nodes = new NodeMap();
		correctNodes.forEach(ref -> {
			if (ref != getSelf()) {
				// schedule to self to catch failure of the node
				Cancellable failTimeout = sendToSelf(new Fail(ref, 0), failTime); // TODO: increase initial failTime?
				nodes.put(ref, new NodeInfo(failTimeout));
			}
			else {
				nodes.put(ref, new NodeInfo());
			}
		});
		correctNodes.remove(getSelf());
		// schedule reminder to perform first Gossip
		gossipTimeout = sendToSelf(new GossipReminder(), gossipTime);
		// setup for catastrophe recovery
		multicastParam = msg.getMulticastParam();
		multicastMaxWait = msg.getMulticastMaxWait();
		multicastWait = 0;
		// schedule reminder to attempt multicast
		multicastTimeout = sendToSelf(new CatastropheReminder(), 1000);

		if (msg.isFaulty()) log.info("onStart complete (faulty, crashes in " + msg.getDelta() + ")");
		else log.info("onStart complete (correct)");
		log.debug("nodes: " + nodes.beatsToString());
	}

	private void onStop() {
		getContext().become(notReady);
		if (selfCrashTimeout != null) selfCrashTimeout.cancel();
		if (gossipTimeout != null) gossipTimeout.cancel();
		if (multicastTimeout != null) multicastTimeout.cancel();
		correctNodes.clear();
		nodes.clear();
	}

	private void onCrash() { // TODO: equivalent to onStop()?
		getContext().become(notReady);
		log.info("onCrash complete");
	}

	private void sendGossip() {
		if (correctNodes.size() < 1) {
			log.warning("Gossip aborted, no correct node available");
			return;
		}
		// increment node's own heartbeat counter
		nodes.get(getSelf()).heartbeat();
		// pick random node
		Random rand = new Random();
		ActorRef gossipNode = correctNodes.get(rand.nextInt(correctNodes.size()));
		// gossip the beats to the random node
		gossipNode.tell(new Gossip(nodes.getBeats()), getSelf());
		log.debug("gossiped to {}: " + nodes.beatsToString(), idFromRef(gossipNode));
		// schedule a new reminder to Gossip
		gossipTimeout = sendToSelf(new GossipReminder(), gossipTime);
	}

	private void onGossip(Gossip msg) {
		Map<ActorRef, Long> gossipedBeats = msg.getBeats();
		correctNodes.forEach(ref -> {
			long gossipedBeatCount = gossipedBeats.get(ref);
			// if a higher heartbeat counter was gossiped, update it
			if (gossipedBeatCount > nodes.get(ref).getBeatCount()) { // TODO: sliding window
				nodes.get(ref).setBeatCount(gossipedBeatCount);
				// restart the timeout
				Fail failMsg = new Fail(ref, nodes.get(ref).getFailId()+1);
				nodes.get(ref).resetTimeout(sendToSelf(failMsg, failTime));
			}
		});
	}

	private void onFail(Fail msg) {
		ActorRef failing = msg.getFailing();
		int failId = msg.getFailId();
		// check if the Fail message was still valid
		if (nodes.get(failing).getFailId() == failId) {
			// remove from correct nodes and report to Tracker
			correctNodes.remove(failing);
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
		double multicastProb = Math.pow((double)multicastWait / multicastMaxWait, multicastParam);
		double rand = Math.random();

		if (rand < multicastProb) {
			// do multicast
			nodes.get(getSelf()).heartbeat();
			multicastWait = 0; // TODO: check
			multicast(new CatastropheMulticast(nodes.getBeats()));
		}
		else {
			// multicast postponed
			multicastWait++;
			sendToSelf(new CatastropheReminder(), 1000);
		}
	}

	private void onMulticast(CatastropheMulticast msg) {
		multicastWait = 0;
		Gossip gossipMsg = new Gossip(msg.getBeats());
		onGossip(gossipMsg);
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
	 * @param delay Schedule to be sent after delay millisecs.
	 *
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
		correctNodes.forEach(ref -> ref.tell(message, getSelf()));
	}
}
