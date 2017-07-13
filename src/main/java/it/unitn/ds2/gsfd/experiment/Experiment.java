package it.unitn.ds2.gsfd.experiment;

import javax.json.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Class that represent a single experiment.
 * Contains the configuration, results and report for the experiment.
 */
public final class Experiment {

	// generate a new random experiment
	public static Experiment generate(Set<String> nodes, boolean pullByGossip, int duration, int seed, int repetition) {
		final Random random = new Random(seed);

		// number of nodes
		final int numberOfNodes = nodes.size();

		// pick a random number of nodes to crash [0, n)
		// NB: we crash at most all except one node
		final int crashes = random.nextInt(numberOfNodes);

		// generate a permutation of nodes
		// the first n will be selected for the crashes
		final List<String> permutation = new ArrayList<>(nodes);
		Collections.sort(permutation);
		Collections.shuffle(permutation, random);

		// generate the crashes
		final List<ExpectedCrash> expectedCrashes = IntStream.of(crashes)
			.boxed()
			.map(permutation::get)
			.map(node -> new ExpectedCrash((long) random.nextInt(duration / 2), node))  // TODO: remove /2
			// TODO: crash WELL before duration - failureDelta?
			.collect(Collectors.toList());

		// return the experiment
		final String id = String.format("nodes-%d__pushpull-%b__duration-%d__seed-%d__repetition-%d",
			numberOfNodes, pullByGossip, duration, seed, repetition);
		return new Experiment(id, numberOfNodes, pullByGossip, duration, expectedCrashes,
			500, 6000,
			true, 3, 10, 0);
		// TODO: proper input of gossipDelta, failureDelta, multicastActive, multicastParam, multicastMaxWait and pickStrategy
	}

	// unique identifier for the experiment
	private final String id;

	// number of nodes that participates to the experiment
	private final int numberOfNodes;

	// gossip strategy to use: push vs push-pull
	private final boolean pushPull;

	// total duration (milliseconds) of the experiment
	private final int duration;

	// scheduled crashes
	private final List<ExpectedCrash> expectedCrashes;

	// reported crashed
	private final List<ReportedCrash> reportedCrashed;

	// frequency of Gossip
	private final long gossipDelta;

	// time to consider a node failed
	private final long failureDelta;

	// if true, multicast will be periodically issued
	private final boolean multicastActive;

	// parameter "a" of probability of multicast (catastrophe recovery)
	private final double multicastParam;

	// maximum number of times a multicast can be postponed
	private final int multicastMaxWait;

	// indicates what probability distribution is used when choosing random nodes
	private final int pickStrategy;

	// start time of the experiment
	private Long start;

	// status of the experiment
	private Long stop;

	// initialize a new experiment
	private Experiment(String id, int numberOfNodes, boolean pushPull,
					   int duration, List<ExpectedCrash> expectedCrashes, long gossipDelta, long failureDelta,
					   boolean multicastActive, double multicastParam, int multicastMaxWait,
					   int pickStrategy) {
		this.id = id;
		this.numberOfNodes = numberOfNodes;
		this.pushPull = pushPull;
		this.duration = duration;
		this.expectedCrashes = expectedCrashes;
		this.reportedCrashed = new LinkedList<>();
		this.gossipDelta = gossipDelta;
		this.failureDelta = failureDelta;
		this.multicastActive = multicastActive;
		this.multicastParam = multicastParam;
		this.multicastMaxWait = multicastMaxWait;
		this.pickStrategy = pickStrategy;
		this.start = null;
		this.stop = null;
	}

	public boolean isPushPull() {
		return pushPull;
	}

	public int getDuration() {
		return duration;
	}

	public List<ExpectedCrash> getExpectedCrashes() {
		return expectedCrashes;
	}

	public long getGossipDelta() {
		return gossipDelta;
	}

	public long getFailureDelta() {
		return failureDelta;
	}

	public boolean isMulticastActive() {
		return multicastActive;
	}

	public double getMulticastParam() {
		return multicastParam;
	}

	public int getMulticastMaxWait() {
		return multicastMaxWait;
	}

	public int getPickStrategy() {
		return pickStrategy;
	}

	public void start() {
		if (start != null) {
			throw new IllegalStateException("Please call the start() method only once per experiment.");
		}
		start = System.currentTimeMillis();
	}

	public void stop() {
		if (stop != null) {
			throw new IllegalStateException("Please call the stop() method only once per experiment.");
		}
		stop = System.currentTimeMillis();
	}

	// report a new crash
	public void addCrash(String node, String reporter) {
		if (start == null) {
			throw new IllegalStateException("Please call the start() method to start the experiment first.");
		}
		final long delta = System.currentTimeMillis() - start;
		reportedCrashed.add(new ReportedCrash(delta, node, reporter));
	}

	/**
	 * Generate the report for the experiment.
	 *
	 * @param factory   Custom JsonWriter factory.
	 * @param directory Path of the directory where to save the generated report.
	 * @throws IOException If something goes wrong with writing the file.
	 */
	public void generateReport(JsonWriterFactory factory, String directory) throws IOException {

		// make sure the experiment was previously terminated
		if (stop == null) {
			throw new IllegalStateException("Please call the stop() method to stop the experiment first.");
		}

		// convert expected crashed to a JSON array
		final JsonArrayBuilder expectedCrashesForReport = Json.createArrayBuilder();
		expectedCrashes.forEach(crash -> expectedCrashesForReport.add(
			Json.createObjectBuilder()
				.add("delta", crash.getDelta())
				.add("node", crash.getNode())
		));

		// convert reported crashed to a JSON array
		final JsonArrayBuilder reportedCrashesForReport = Json.createArrayBuilder();
		reportedCrashed.forEach(crash -> reportedCrashesForReport.add(
			Json.createObjectBuilder()
				.add("delta", crash.getDelta())
				.add("node", crash.getNode())
				.add("reporter", crash.getReporter())
		));

		// generate the JSON report
		final JsonObject report = Json.createObjectBuilder()
			.add("id", id)
			.add("settings", Json.createObjectBuilder()
				.add("number_of_nodes", numberOfNodes)
				.add("duration", duration)
				.add("push_pull", pushPull)
				.add("gossip_delta", gossipDelta)
				.add("failure_delta", failureDelta)
				.add("multicast_parameter", multicastParam)
				.add("multicast_max_wait", multicastMaxWait)
			)
			.add("result", Json.createObjectBuilder()
				.add("start_time", start)
				.add("end_time", stop)
				.add("expected_crashes", expectedCrashesForReport)
				.add("reported_crashes", reportedCrashesForReport)
			)
			.build();

		// write the report to the file
		try (final FileWriter fileWriter = new FileWriter(directory + File.separator + id + ".json")) {
			try (final JsonWriter jsonWriter = factory.createWriter(fileWriter)) {
				jsonWriter.writeObject(report);
			}
		}
	}

	@Override
	public String toString() {
		return String.format("nodes=%d, duration=%ds, push_pull=%s, expected_crashes=%d",
			numberOfNodes, duration, pushPull, expectedCrashes.size());
	}
}
