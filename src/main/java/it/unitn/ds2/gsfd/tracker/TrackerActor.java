package it.unitn.ds2.gsfd.tracker;

import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.DiagnosticLoggingAdapter;
import akka.event.Logging;
import akka.japi.Creator;
import it.unitn.ds2.gsfd.messages.BaseMessage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Akka Actor that implements the node's behaviour.
 */
public final class TrackerActor extends UntypedActor {

	// Unique identifier for this node
	private final int id;

	// Logger, used for debug proposes.
	private final DiagnosticLoggingAdapter logger;

	/**
	 * Create a new node Actor.
	 *
	 * @param id Unique identifier to assign to this node.
	 */
	private TrackerActor(int id) throws IOException {

		// initialize values
		this.id = id;

		// TODO initialize the tracker

		// setup logger context
		this.logger = Logging.getLogger(this);
		final Map<String, Object> mdc = new HashMap<String, Object>() {{
			put("actor", "Main [" + id + "]:");
		}};
		logger.setMDC(mdc);
		logger.info("Tracker \"{}\" started", id);
	}

	/**
	 * Initialize a new node which will register itself to the Tracker node.
	 *
	 * @param id             Id of the node.
	 * @return Props.
	 */
	public static Props init(final int id) {
		return Props.create(new Creator<TrackerActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TrackerActor create() throws Exception {
				return new TrackerActor(id);
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

}
