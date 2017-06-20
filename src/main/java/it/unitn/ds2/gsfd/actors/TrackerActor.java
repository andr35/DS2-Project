package it.unitn.ds2.gsfd.actors;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.DiagnosticLoggingAdapter;
import akka.event.Logging;
import akka.japi.Creator;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import it.unitn.ds2.gsfd.experiment.ExpectedCrash;
import it.unitn.ds2.gsfd.experiment.Experiment;
import it.unitn.ds2.gsfd.experiment.StartExperiment;
import it.unitn.ds2.gsfd.experiment.StopExperiment;
import it.unitn.ds2.gsfd.messages.Registration;
import it.unitn.ds2.gsfd.messages.ReportCrash;
import it.unitn.ds2.gsfd.messages.Start;
import it.unitn.ds2.gsfd.messages.Stop;
import scala.concurrent.duration.Duration;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Tracker: this actor is responsible to track the other nodes,
 * bootstrap the experiments, collect the results and generate reports.
 */
public final class TrackerActor extends AbstractActor implements MyActor {

	// initialize the Tracker node
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

	// tracked nodes
	private final Set<ActorRef> nodes;

	// configuration
	private final Config config;
	private final int expectedNumberOfNodes;

	// list of experiments to perform
	private List<Experiment> experiments;
	private Experiment current;


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

		// initialize tracked nodes
		this.nodes = new HashSet<>();

		// load the configuration
		this.config = ConfigFactory.load();
		this.expectedNumberOfNodes = config.getInt("tracker.nodes");
	}

	@Override
	public void preStart() throws Exception {
		super.preStart();
		log.info("Tracker started... expect {} nodes to start the experiments", expectedNumberOfNodes);
	}

	/**
	 * For each type of message, call the relative callback
	 * to keep this method short and clean.
	 */
	@Override
	public Receive createReceive() {
		return receiveBuilder()
			.match(StartExperiment.class, msg -> onExperimentStart(msg.getExperiment()))
			.match(StopExperiment.class, msg -> onExperimentEnd(msg.getExperiment()))
			.match(Registration.class, msg -> onNodeRegistration())
			.match(ReportCrash.class, this::onReportCrash)
			.matchAny(msg -> log.warning("Received unknown message -> " + msg))
			.build();
	}

	private void onReady() {

		// load the configuration
		final long duration = config.getLong("tracker.duration");
		final int experiments = config.getInt("tracker.experiments");
		final int repetitions = config.getInt("tracker.experiments");
		final int seed = config.getInt("tracker.initial-seed");

		// extract participants' ids
		final Set<String> ids = nodes.stream().map(this::idFromRef).collect(Collectors.toSet());

		// generate the experiments
		this.experiments = new ArrayList<>(experiments * repetitions);
		for (int i = 0; i < experiments; i++) {
			final Experiment current = Experiment.generate(ids, duration, i + seed);
			this.experiments.add(current);

			// TODO: repetitions
		}

		// start the experiments
		onExperimentStart(0);
	}

	private void onExperimentStart(int index) {
		log.info("Start experiment {} of {}", index + 1, experiments.size());

		// extract the experiment
		current = experiments.get(index);
		final List<ExpectedCrash> expectedCrashes = current.getExpectedCrashes();
		final Map<String, Long> crashesByNode = expectedCrashes.stream()
			.collect(Collectors.toMap(ExpectedCrash::getNode, ExpectedCrash::getDelta));

		// start the experiment
		nodes.forEach(node -> {
			final String id = idFromRef(node);
			if (crashesByNode.containsKey(id)) {
				node.tell(Start.crash(crashesByNode.get(id)), getSelf());
			} else {
				node.tell(Start.normal(), getSelf());
			}
		});

		// schedule the end of the experiment
		getContext().system().scheduler().scheduleOnce(
			Duration.create(experiments.get(index).getDuration(), TimeUnit.MILLISECONDS),
			getSelf(),
			new StopExperiment(index),
			getContext().system().dispatcher(),
			getSelf()
		);
	}

	private void onExperimentEnd(int index) {
		log.info("Stop experiment {} of {}", index + 1, experiments.size());

		// send stop to all the nodes
		nodes.forEach(node -> node.tell(new Stop(), getSelf()));

		// generate the report
		experiments.get(index).generateReport();

		// check if this was the last experiment... in this case shutdown the system
		if (index + 1 == experiments.size()) {
			log.warning("No more experiment to perform");
			// TODO: stop the system
		}

		// wait a bit before starting the next experiment
		// TODO: it this time ok?
		else {
			log.debug("Wait some time before starting the next experiment...");
			getContext().system().scheduler().scheduleOnce(
				Duration.create(5, TimeUnit.SECONDS),
				getSelf(),
				new StartExperiment(index + 1),
				getContext().system().dispatcher(),
				getSelf()
			);
		}
	}

	private void onNodeRegistration() {

		// too many nodes... there is a problem!
		if (nodes.size() >= expectedNumberOfNodes) {
			log.error("Too many node joined already... can not accept Node {}", idFromRef(getSender()));
		}

		// correct status...
		else {
			log.debug("Registration of Node {}", idFromRef(getSender()));

			// add the new node to the tracked ones
			nodes.add(getSender());

			// check if I am ready to start the experiments
			if (nodes.size() == expectedNumberOfNodes) {
				log.info("Got {} of {} nodes. Ready to start the experiment.", nodes.size(), expectedNumberOfNodes);
				onReady();
			}
		}
	}

	private void onReportCrash(ReportCrash msg) {

		// TODO: put in the start and stop the experiment number???
		current.addCrash(idFromRef(msg.getNode()), idFromRef(getSender()));
	}
}
