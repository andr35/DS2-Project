package it.unitn.ds2.gsfd.node;

import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.DiagnosticLoggingAdapter;
import akka.event.Logging;
import akka.japi.Creator;
import it.unitn.ds2.gsfd.messages.BaseMessage;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;


/**
 * Akka Actor that implements the node's behaviour.
 */
public final class NodeActor extends UntypedActor {

	// Unique identifier for this node
	private final int id;

	// Address of the Tracker node
	private final String trackerAddress;

	// Logger, used for debug proposes.
	private final DiagnosticLoggingAdapter logger;

	/**
	 * Create a new node Actor.
	 *
	 * @param id Unique identifier to assign to this node.
	 */
	private NodeActor(int id, String trackerAddress) throws IOException {

		// initialize values
		this.id = id;
		this.trackerAddress = trackerAddress;

		// TODO register node to the tracker

		// setup logger context
		this.logger = Logging.getLogger(this);
		final Map<String, Object> mdc = new HashMap<String, Object>() {{
			put("actor", "Main [" + id + "]:");
		}};
		logger.setMDC(mdc);
		logger.info("Main \"{}\" started", id);
	}

	/**
	 * Initialize a new node which will register itself to the Tracker node.
	 *
	 * @param id             Id of the node.
	 * @param trackerAddress Akka address of the tracker to contact.
	 * @return Props.
	 */
	public static Props init(final int id, String trackerAddress) {
		return Props.create(new Creator<NodeActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public NodeActor create() throws Exception {
				return new NodeActor(id, trackerAddress);
			}
		});
	}

	/**
	 * For each type of message, call the relative callback
	 * to keep this method short and clean.
	 *
	 * @param message Incoming message.
	 */
	@Override
	public void onReceive(Object message) {
		if (message instanceof BaseMessage) {
			onMessage((BaseMessage) message);
		} else if (message instanceof BaseMessage) {
			onMessage((BaseMessage) message);
		} else {
			unhandled(message);
		}
	}

	private void onMessage(BaseMessage message) {
		logger.debug("Message from node [{}]", message.getSenderID());
	}

	/**
	 * Send the given message to all the other nodes.
	 *
	 * @param message Message to send in multicast.
	 */
	private void multicast(Serializable message) {
		// TODO implement
	}

	/**
	 * Reply to the actor that sent the last message.
	 *
	 * @param reply Message to sent back.
	 */
	private void reply(Serializable reply) {
		getSender().tell(reply, getSelf());
	}

}
