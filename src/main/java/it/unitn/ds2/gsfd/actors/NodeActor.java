package it.unitn.ds2.gsfd.actors;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.event.DiagnosticLoggingAdapter;
import akka.event.Logging;
import akka.japi.Creator;
import it.unitn.ds2.gsfd.messages.*;
import it.unitn.ds2.gsfd.messages.Shutdown;
import it.unitn.ds2.gsfd.protocol.*;
import it.unitn.ds2.gsfd.utils.NodeMap;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Node: this actor implements the gossip style failure detector system
 * described in the paper "A Gossip-Style Failure Detection Service".
 * The node simulates crashes and reports the detected one to the central tracker.
 */
public final class NodeActor extends AbstractActor implements BaseActor {

	/**
	 * Initialize a new node which will register itself to the Tracker node.
	 * Once registered, the node will wait for commands from the tracker.
	 * The tracker starts and stops experiments, schedules crashes and collect
	 * the detected failures.
	 *
	 * @param trackerAddress Akka address of the tracker to contact.
	 * @return Akka Props object.
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
	// if ready, accepts messages other than StartExperiment and StopExperiment
	private Receive ready;
	private Receive notReady;

	// timeout to simulate crash; cancelled by StopExperiment message.
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

	// if true, activates countermeasures for catastrophe
	private boolean catastrophe;

	// parameter used to decide if the node will multicast
	private double multicastParam;

	// number of times multicast was postponed
	private long multicastWait;

	// parameter used to decide the maximum number of times multicast can be postponed
	private long multicastMaxWait;

	// timeout to issue another try for multicast
	private Cancellable multicastTimeout;

	// grace time, in catastrophic events, to decide if the node has really crashed
	private long missTime;

	// if true, the node replies to the gossip sender
	private boolean pullByGossip;

	// tells what strategy to use when choosing a (random) node to gossip (see NodeMap's pickNode)
	private int pickStrategy;

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

		// messages to accept in the NOT_READY state
		// (before an experiment or while a crash is simulated)
		notReady = receiveBuilder()
			.match(Shutdown.class, msg -> onShutdown())
			.match(StartExperiment.class, this::onStart)
			.match(StopExperiment.class, msg -> onStop())
			.matchAny(msg -> log.warning("Dropped message -> " + msg))
			.build();

		// messages to accept in the READY state (during an experiment)
		ready = receiveBuilder()
			.match(Shutdown.class, msg -> onShutdown())
			.match(StartExperiment.class, this::onStart)
			.match(StopExperiment.class, msg -> onStop())
			.match(SelfCrash.class, msg -> onCrash())
			.match(GossipReminder.class, msg -> sendGossip())
			.match(Gossip.class, this::onGossip)
			.match(GossipReply.class, this::onGossipReply)
			.match(Fail.class, this::onFail)
			.match(Miss.class, this::onMiss)
			.match(Cleanup.class, this::onCleanup)
			.match(CatastropheReminder.class, msg -> sendMulticast())
			.match(CatastropheMulticast.class, this::onMulticast)
			.match(CatastropheReply.class, this::onMulticastReply)
			.matchAny(msg -> log.error("Received unknown message -> " + msg))
			.build();

		// at the beginning, we must wait for the tracker -> NOT_READY
		getContext().become(notReady);
	}

	@Override
	public void preStart() throws Exception {
		super.preStart();
		log.info("StartExperiment... register on the Tracker");
		sendToTracker(new Registration());
	}

	/**
	 * For each type of message, call the relative callback
	 * to keep this method short and clean.
	 */
	@Override
	public Receive createReceive() {
		return receiveBuilder()
			.match(Shutdown.class, msg -> onShutdown())
			.match(StartExperiment.class, msg -> getContext().become(ready))
			.match(StopExperiment.class, msg -> getContext().become(notReady))
			.match(SelfCrash.class, msg -> getContext().become(notReady))
			.matchAny(msg -> log.error("Received unknown message -> " + msg))
			.build();
	}

	private void onShutdown() {
		log.warning("Tracker requested to shutdown the system... terminate");
		getContext().getSystem().terminate();
	}

	private void onStart(StartExperiment msg) {
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
				nodes.get(ref).setTimeout(failTimeout);
			}
		});

		// schedule reminder to perform first Gossip
		gossipTimeout = sendToSelf(new GossipReminder(), gossipTime);

		// set strategy for gossip's random node choice
		pickStrategy = msg.getPickStrategy();

		// setup for catastrophe recovery
		catastrophe = msg.isCatastrophe();
		if (catastrophe) {
			log.info("multicast is active");
			multicastParam = msg.getMulticastParam();
			multicastMaxWait = msg.getMulticastMaxWait();
			multicastWait = 0;
			missTime = failTime; // TODO: how to compute missTime?

			// schedule reminder to attempt multicast
			multicastTimeout = sendToSelf(new CatastropheReminder(), 1000);
		} else {
			log.info("multicast is not active");
		}

		// debug
		log.info("pick strategy: " + pickStrategy);
		if (delta != null) log.info("onStart complete (faulty, crashes in " + msg.getDelta() + ")");
		else log.info("onStart complete (correct)");
		log.debug("nodes: " + beatsToString(nodes.getBeats()));
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
		sendToTracker(new Crash());
		log.info("onCrash complete");
	}

	private void sendGossip() {

		// increment node's own heartbeat counter
		nodes.get(getSelf()).heartbeat();

		// TODO: check that this works with the experiments (resetting quiescence)
		// pick random correct node
		ActorRef gossipNode = nodes.pickNode(pickStrategy);
		if (gossipNode != null) {

			// gossip the beats to the random node
			gossipNode.tell(new Gossip(nodes.getBeats()), getSelf());
			log.debug("gossip to {}: " + beatsToString(nodes.getBeats()), idFromRef(gossipNode));

			// lower the probability of gossiping the same node soon
			nodes.get(gossipNode).resetQuiescence();

			// schedule a new reminder to Gossip
			gossipTimeout = sendToSelf(new GossipReminder(), gossipTime);

		} else {
			log.info("Gossip stopped (no correct node to gossip)");
		}
	}

	private void onGossip(Gossip msg) {
		log.debug("gossip from {}, beats={}, push_pull={}",
			idFromRef(getSender()), beatsToString(msg.getBeats()), pullByGossip);

		// this method update both the beats for all nodes and resets the quiescence for any updated
		updateBeats(msg.getBeats());

		if (pullByGossip) {

			// gossip back (pull strategy)
			reply(new GossipReply(nodes.getBeats()));
		}
	}

	/**
	 * This method is called to handle a gossip reply.
	 * Used only if push-pull strategy is active (pullByGossip).
	 *
	 * @param msg Gossip-type message (heartbeats).
	 */
	private void onGossipReply(GossipReply msg) {
		updateBeats(msg.getBeats());
		log.debug("gossip (reply) from {}", idFromRef(getSender()));
	}

	/**
	 * This method is called when the failure detector detects
	 * that a node is crashed. This is a self-message.
	 *
	 * @param msg Details with the node that is though to be crashed.
	 */
	private void onFail(Fail msg) {
		ActorRef failing = msg.getFailing();
		long failId = msg.getFailId();

		// check if the Fail message was still valid
		if (nodes.get(failing).getTimeoutId() == failId) {
			if (catastrophe) {

				// stop gossiping the suspected node
				nodes.setMissing(failing);

				// give the system more time to decide if the node is failed
				Miss missMsg = new Miss(failing, nodes.get(failing).getTimeoutId() + 1);
				nodes.get(failing).resetTimeout(sendToSelf(missMsg, missTime));
				log.info("Node {} is missing", idFromRef(failing));
			} else {

				// remove from correct nodes and report to Tracker
				nodes.setFailed(failing);
				sendToTracker(new CrashReport(failing));

				// schedule message to remove the node from the heartbeat map
				Cleanup cleanMsg = new Cleanup(failing, nodes.get(failing).getTimeoutId() + 1);
				nodes.get(failing).resetTimeout(sendToSelf(cleanMsg, cleanupTime));
				log.info("Node {} reported as failed", idFromRef(failing));
			}

		} else {
			log.warning("Dropped Fail message (expected Id: {}, found: {}) -> {}",
				nodes.get(failing).getTimeoutId(), failId, msg.toString());
		}
	}

	private void onMiss(Miss msg) {
		ActorRef missing = msg.getMissing();
		long missId = msg.getMissId();

		// check if the Miss message was still valid
		if (nodes.get(missing).getTimeoutId() == missId) {
			log.info("Node {} reported as failed (was missing)", idFromRef(missing));

			// missing node is definitely considered crashed
			// remove from correct nodes and report to Tracker
			nodes.setFailed(missing);
			sendToTracker(new CrashReport(missing));

			// schedule cleanup
			Cleanup cleanMsg = new Cleanup(missing, nodes.get(missing).getTimeoutId() + 1);
			nodes.get(missing).resetTimeout(sendToSelf(cleanMsg, cleanupTime));
		}
	}

	/**
	 * This method is called when cleanup timeout is expired for a node
	 * after it is believed to have crashed. This is a self-message.
	 *
	 * @param msg Details with the node that is to be completely removed.
	 */
	private void onCleanup(Cleanup msg) {
		ActorRef failed = msg.getFailed();
		long cleanId = msg.getCleanId();

		// check if the Cleanup message was still valid
		if (nodes.get(failed).getTimeoutId() == cleanId) {
			nodes.remove(failed);
			log.info("Node {} cleanup", idFromRef(failed));
		} else {
			log.warning("Dropped Cleanup message (expected Id: {}, found: {}) -> {}",
				nodes.get(failed).getTimeoutId(), cleanId, msg.toString());
		}
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

			// TODO: check that this works with the experiments (resetting quiescence)
			// even the probability of gossip to any node
			nodes.getUpdatableNodes().forEach(ref -> nodes.get(ref).resetQuiescence());
			log.debug("multicast: " + beatsToString(nodes.getBeats()));
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
	 * @param msg Message with nodes beats.
	 */
	private void onMulticast(CatastropheMulticast msg) {
		multicastWait = 0;
		updateBeats(msg.getBeats());

		// send back node's heartbeats to multicast originator
		// this is always done regardless of push-pull option
		reply(new CatastropheReply(nodes.getBeats()));
		log.debug("multicast from {}, beats={}", idFromRef(getSender()), beatsToString(msg.getBeats()));
	}

	private void onMulticastReply(CatastropheReply msg) {
		updateBeats(msg.getBeats());

		// debug
		log.debug("multicast (reply) from {}, beats={}", idFromRef(getSender()), beatsToString(msg.getBeats()));
	}

	private void updateBeats(Map<ActorRef, Long> gossipedBeats) {
		nodes.getUpdatableNodes().forEach((ref) -> {
			if (gossipedBeats.containsKey(ref)) {
				long beat = gossipedBeats.get(ref);

				// if a higher heartbeat counter was gossiped, update it
				if (beat > nodes.get(ref).getBeatCount()) {
					nodes.get(ref).setBeatCount(beat);

					// lower the probability of gossiping the same node soon
					nodes.get(ref).resetQuiescence();

					// if the node was missing, change it back to a normal node
					nodes.unsetMissing(ref);

					// restart the timeout
					Fail failMsg = new Fail(ref, nodes.get(ref).getTimeoutId() + 1);
					nodes.get(ref).resetTimeout(sendToSelf(failMsg, failTime));
				} else {

					// no heartbeat update, increase probability of gossiping the node
					nodes.get(ref).quiescent();
				}
			}
		});
	}

	private String beatsToString(Map<ActorRef, Long> beats) {
		final StringBuilder result = new StringBuilder("{");
		for (Map.Entry<ActorRef, Long> entry : beats.entrySet()) {
			ActorRef ref = entry.getKey();
			Long beat = entry.getValue();
			result.append(" (").append(idFromRef(ref)).append(", ").append(beat).append(") ");
		}
		return result.toString() + "}";
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
		nodes.getUpdatableNodes().forEach(ref -> ref.tell(message, getSelf()));
	}
}
