package it.unitn.ds2.gsfd.actors;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.DiagnosticLoggingAdapter;
import akka.event.Logging;
import akka.japi.Creator;
import it.unitn.ds2.gsfd.messages.Registration;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracker: this actor is responsible to track the other nodes,
 * bootstrap the experiments, collect the results and generate reports.
 */
public final class TrackerActor extends AbstractActor implements MyActor {

	/**
	 * Initialize the Tracker node.
	 *
	 * @return Props.
	 */
	public static Props init() {
		return Props.create(new Creator<TrackerActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TrackerActor create() {
				return new TrackerActor();
			}
		});
	}

	// log, used for debug proposes
	private final DiagnosticLoggingAdapter log;

	// constructor is private... use the Props factory
	private TrackerActor() {

		// extract my identifier
		final String id = idFromRef(getSelf());

		// setup log context
		final Map<String, Object> mdc = new HashMap<String, Object>() {{
			put("actor", "Tracker [" + id + "]:");
		}};
		this.log = Logging.getLogger(this);
		this.log.setMDC(mdc);
	}

	@Override
	public void preStart() throws Exception {
		super.preStart();
		log.info("Tracker started...");
	}

	/**
	 * For each type of message, call the relative callback
	 * to keep this method short and clean.
	 */
	@Override
	public Receive createReceive() {
		return receiveBuilder()
			.match(Registration.class, msg -> onNodeRegistration())
			.matchAny(msg -> log.warning("Received unknown message -> " + msg))
			.build();
	}

	private void onNodeRegistration() {
		log.debug("Registration of Node {}", idFromRef(getSender()));

		// TODO: track it
	}
}
