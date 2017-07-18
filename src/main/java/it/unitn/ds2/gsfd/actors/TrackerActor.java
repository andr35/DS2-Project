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
import it.unitn.ds2.gsfd.experiment.ScheduleExperimentStart;
import it.unitn.ds2.gsfd.experiment.ScheduleExperimentStop;
import it.unitn.ds2.gsfd.messages.*;
import it.unitn.ds2.gsfd.messages.Shutdown;
import scala.concurrent.duration.Duration;

import javax.json.Json;
import javax.json.JsonWriterFactory;
import javax.json.stream.JsonGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Tracker: this actor is responsible to track the other nodes,
 * bootstrap the experiments, collect the results and generate reports.
 */
public final class TrackerActor extends AbstractActor implements BaseActor {

	/**
	 * Initialize the Tracker node. Once initialized, the tracker will generate
	 * a set of experiments, wait for a given number of nodes to register and
	 * then start the experiments in some order. At the end of each experiment,
	 * a report that contains the scheduled crashes and the reported once is
	 * generated and saved as a file.
	 *
	 * @return Akka Props object.
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

	// tracked nodes
	private final Set<ActorRef> nodes;

	// configuration
	private final Config config;
	private final long millisBetweenExperiments;
	private final int expectedNumberOfNodes;
	private final String reportDirectory;

	// list of experiments to perform
	private List<Experiment> experiments;
	private Experiment current;

	// json writer -> pretty json files
	private final JsonWriterFactory jsonWriterFactory;

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
		this.millisBetweenExperiments = config.getLong("tracker.time-between-experiments");
		this.expectedNumberOfNodes = config.getInt("tracker.nodes");
		this.reportDirectory = config.getString("tracker.report-path");

		// make sure the directory for the report exists
		try {
			Files.createDirectories(Paths.get(reportDirectory));
		} catch (IOException e) {
			log.error("Can not create the directory for the reports: {}", reportDirectory, e);
			throw new RuntimeException(e);
		}

		// create the custom JSON writer factory
		final Map<String, Object> properties = new HashMap<String, Object>(1) {{
			put(JsonGenerator.PRETTY_PRINTING, true);
		}};
		this.jsonWriterFactory = Json.createWriterFactory(properties);
	}

	@Override
	public void preStart() throws Exception {
		super.preStart();
		log.info("Tracker started... expect {} nodes to start the experiments", expectedNumberOfNodes);
	}

	@Override
	public Receive createReceive() {
		return receiveBuilder()
			.match(Registration.class, msg -> onNodeRegistration())
			.match(ScheduleExperimentStart.class, msg -> onExperimentStart(msg.getExperiment()))
			.match(ScheduleExperimentStop.class, msg -> onExperimentEnd(msg.getExperiment()))
			.match(Crash.class, msg -> onCrash())
			.match(CrashReport.class, this::onReportCrash)
			.matchAny(msg -> log.error("Received unknown message -> " + msg))
			.build();
	}

	/**
	 * This method is called when a node registers itself to the tracker.
	 * The tracker will either accept the registration (if the experiments
	 * are not yet started) or reject it. When all nodes have registered,
	 * the method {@link #onReady()} is called and the experiments are started.
	 */
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

	/**
	 * This method is called when the tracker is ready to start the experiments,
	 * i.e. all nodes have registered to the tracker. The tracker generates the
	 * experiments and starts them in order, one at a time.
	 */
	private void onReady() {

		// log for debug
		log.warning("Generating experiments with {}", config.getObject("tracker").toString());

		// extract participants' ids
		final Set<String> ids = nodes.stream().map(this::idFromRef).collect(Collectors.toSet());

		// load the configuration
		final int duration = config.getInt("tracker.duration");
		final int numberOfExperiments = config.getInt("tracker.number-of-experiments");
		final int repetitions = config.getInt("tracker.repetitions");
		final int initialSeed = config.getInt("tracker.initial-seed");
		final long gossipDelta = config.getLong("tracker.gossip-delta");
		final int minFailureRounds = config.getInt("tracker.min-failure-rounds");
		final int maxFailureRounds = config.getInt("tracker.max-failure-rounds");
		final long missDelta = gossipDelta * config.getLong("tracker.miss-delta-rounds");

		// generate the experiments
		this.experiments = new ArrayList<>();

		// use different seeds
		for (int seed = initialSeed; seed < initialSeed + numberOfExperiments; seed++) {

			// repeat each experiment some times
			for (int repetition = 0; repetition < repetitions; repetition++) {

				// should I crash only 1 node or 2/3 of them
				for (boolean simulateCatastrophe : new boolean[]{false, true}) {

					// use different values for failureDelta... multiples of gossipDelta
					for (int round = maxFailureRounds; round >= minFailureRounds; round -= 2) {

						// use different strategies (push vs push_pull, how to select the nodes)
						for (boolean pushPull : new boolean[]{false, true}) {

							// TODO: replace with enum
							for (int pickStrategy = 0; pickStrategy < 3; pickStrategy++) {

								// should the node enable the protocol to resist to catastrophes
								for (boolean enableMulticast : new boolean[]{false, true}) {

									// build the experiment with the parameters used so far...
									final Experiment.Builder builder = new Experiment.Builder()
										.nodes(ids)
										.seed(seed)
										.repetition(repetition)
										.simulateCatastrophe(simulateCatastrophe)
										.duration(duration)
										.gossipDelta(gossipDelta)
										.failureDelta(gossipDelta * round)
										.missDelta(missDelta)
										.pushPull(pushPull)
										.pickStrategy(pickStrategy)
										.enableMulticast(enableMulticast);

									// only in case we enable the multicast, play with its parameters
									if (enableMulticast) {

										// try different values for the parameter a -> regulate the first expected multicast
										// TODO: put correct ones
										for (int a : new int[]{1, 2}) {

											// try different max waits -> regulate the maximum expected time for the first a multicast
											for (int maxWait : new int[]{1, 2}) {

												// finally, generate the experiment
												final Experiment experiment = builder
													.multicastParam(a)
													.multicastMaxWait(maxWait)
													.build();

												// and add it to the experiments
												experiments.add(experiment);
											}
										}
									}

									// use fixed values
									else {

										// finally, generate the experiment
										final Experiment experiment = builder
											.multicastParam(0)
											.multicastMaxWait(0)
											.build();

										// and add it to the experiments
										experiments.add(experiment);
									}
								}
							}
						}
					}
				}
			}
		}

		// log
		log.warning("Generated {} experiments...", experiments.size());

		// start the experiments
		onExperimentStart(0);
	}

	/**
	 * This method is called when a the tracker should start a new experiment.
	 * The tracker schedules schedules the experiments one after the other, but
	 * waits some time between the end of one experiment and the start of the
	 * next one to make sure all nodes have time to reset their internal status.
	 *
	 * @param index Index of the experiment to start.
	 */
	private void onExperimentStart(int index) {

		// extract the current experiment
		current = experiments.get(index);

		// extract the details of the experiment
		final List<ExpectedCrash> expectedCrashes = current.getExpectedCrashes();
		final Map<String, Long> crashesByNode = expectedCrashes.stream()
			.collect(Collectors.toMap(ExpectedCrash::getNode, ExpectedCrash::getDelta));
		final long gossipTime = current.getGossipDelta();
		final long failTime = current.getFailureDelta();
		final long missTime = current.getMissDelta();
		final double multicastParam = current.getMulticastParam();
		final int multicastMaxWait = current.getMulticastMaxWait();
		final int pickStrategy = current.getPickStrategy();

		// log the start of the experiment...
		log.warning("Start experiment {} of {} [{}]", index + 1, experiments.size(), current.toString());

		// start the experiment
		current.start();
		nodes.forEach(node -> {
			final String id = idFromRef(node);
			if (crashesByNode.containsKey(id)) {
				node.tell(StartExperiment.crash(current.isPushPull(), crashesByNode.get(id), nodes, gossipTime,
					failTime, current.isEnableMulticast(), missTime, multicastParam, multicastMaxWait, pickStrategy), getSelf());
			} else {
				node.tell(StartExperiment.normal(current.isPushPull(), nodes, gossipTime, failTime,
					current.isEnableMulticast(), missTime, multicastParam, multicastMaxWait, pickStrategy), getSelf());
			}
		});

		// schedule the end of the experiment
		getContext().system().scheduler().scheduleOnce(
			Duration.create(experiments.get(index).getDuration(), TimeUnit.MILLISECONDS),
			getSelf(),
			new ScheduleExperimentStop(index),
			getContext().system().dispatcher(),
			getSelf()
		);
	}

	/**
	 * This method is called when the tracker should stop an ongoing experiment.
	 * The tracker will inform all nodes to stop their activities and reset the
	 * internal state. Then, it will generate the report. Finally, it will
	 * schedule the next experiment after some time.
	 *
	 * @param index Index of the experiment to stop.
	 */
	private void onExperimentEnd(int index) throws IOException {

		// log the end of the experiment...
		log.warning("StopExperiment experiment {} of {}", index + 1, experiments.size());

		// send stop to all the nodes
		nodes.forEach(node -> node.tell(new StopExperiment(), getSelf()));

		// generate the report
		experiments.get(index).stop();
		experiments.get(index).generateReport(jsonWriterFactory, reportDirectory);
		log.debug("Generated report for experiment {}", index + 1);

		// check if this was the last experiment... in this case shutdown the system
		if (index + 1 == experiments.size()) {
			log.warning("No more experiment to perform... shutdown the cluster");
			nodes.forEach(node -> node.tell(new Shutdown(), getSelf()));
			getContext().getSystem().terminate();
		}

		// wait a bit before starting the next experiment
		else {
			log.debug("Wait {} seconds before starting the next experiment...", millisBetweenExperiments / 1000);
			getContext().system().scheduler().scheduleOnce(
				Duration.create(millisBetweenExperiments, TimeUnit.MILLISECONDS),
				getSelf(),
				new ScheduleExperimentStart(index + 1),
				getContext().system().dispatcher(),
				getSelf()
			);
		}
	}

	/**
	 * This method is called when a node simulates a crash. This is used ONLY as debug,
	 * since the tracker already knows the simulated crashes.
	 */
	private void onCrash() {
		log.info("Node {} crash", idFromRef(getSender()));
	}

	/**
	 * This method is called when a node detects a failure and reports it to the tracker.
	 * The tracker simply add the failure report to the experiment in order to include
	 * it in the final report.
	 *
	 * @param msg Crash report, with the details of the crashed node and who detected it.
	 */
	private void onReportCrash(CrashReport msg) {
		log.info("Report crash of node {} (from node {})", idFromRef(msg.getNode()), idFromRef(getSender()));

		// memorize the crash report
		if (current != null) {
			current.addCrash(idFromRef(msg.getNode()), idFromRef(getSender()));
		} else {
			log.error("Crash report outside an experiment... there must be an error!");
		}
	}
}
