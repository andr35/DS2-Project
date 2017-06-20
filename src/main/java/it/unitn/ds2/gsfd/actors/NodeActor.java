package it.unitn.ds2.gsfd.actors;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.DiagnosticLoggingAdapter;
import akka.event.Logging;
import akka.japi.Creator;
import it.unitn.ds2.gsfd.messages.Registration;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

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
			.matchAny(msg -> log.warning("Received unknown message -> " + msg))
			.build();
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
		// TODO implement
	}
}
