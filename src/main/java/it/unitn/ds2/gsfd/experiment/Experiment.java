package it.unitn.ds2.gsfd.experiment;

import org.jetbrains.annotations.NotNull;

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

	// unique identifier for the experiment
	private final String id;


	// random seed used to generate the crashes
	private final int seed;

	// number of the repetition of the experiment
	private final int repetition;


	// should the experiment crash a lot of nodes
	private final boolean simulateCatastrophe;

	// number of nodes that participates to the experiment
	private final int numberOfNodes;

	// total duration (milliseconds) of the experiment
	private final int duration;

	// frequency of Gossip
	private final long gossipDelta;

	// time to consider a node failed
	private final long failureDelta;

	// time to consider a missing node failed (enableMulticast recovery)
	private final long missDelta;

	// gossip strategy to use: push vs push-pull
	private final boolean pushPull;

	// indicates what probability distribution is used when choosing random nodes
	private final int pickStrategy;

	// if true, multicast will be periodically issued
	private final boolean enableMulticast;

	// parameter "a" of probability of multicast (enableMulticast recovery)
	private final double multicastParam;

	// maximum number of times a multicast can be postponed (enableMulticast recovery)
	private final int multicastMaxWait;


	// scheduled crashes
	private final List<ExpectedCrash> expectedCrashes;

	// reported crashed
	private final List<ReportedCrash> reportedCrashed;


	// start time of the experiment
	private Long start;

	// status of the experiment
	private Long stop;


	// private constructor -> use the builder instead
	private Experiment(Builder builder) {

		////////////////////////////////////////////////////////////////////
		// generate the experiment
		////////////////////////////////////////////////////////////////////

		// generate an ID for the experiment
		final String id = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "");

		// number of nodes
		final int numberOfNodes = builder.nodes.size();

		// if simulate enableMulticast -> crash 2/3 nodes, else just one
		final int crashes = builder.simulateCatastrophe ? (int) Math.ceil(numberOfNodes / 3.0) : 1;

		// initialize a random generator with the seed (to decide when to crash the nodes)
		final Random random = new Random(builder.seed);

		// get the crash time (inside the first half of the experiment duration)
		final long crashTime = (long) random.nextInt(builder.duration / 2);

		// generate a permutation of nodes
		// the first n will be selected for the crashes
		final List<String> permutation = new ArrayList<>(builder.nodes);
		Collections.sort(permutation);
		Collections.shuffle(permutation, random);

		// generate the crashes
		final List<ExpectedCrash> expectedCrashes = IntStream.of(crashes)
			.boxed()
			.map(permutation::get)
			.map(node -> new ExpectedCrash(crashTime, node))
			.collect(Collectors.toList());


		////////////////////////////////////////////////////////////////////
		// initialization
		////////////////////////////////////////////////////////////////////
		this.id = id;
		this.seed = builder.seed;
		this.repetition = builder.repetition;
		this.simulateCatastrophe = builder.simulateCatastrophe;
		this.numberOfNodes = numberOfNodes;
		this.duration = builder.duration;
		this.gossipDelta = builder.gossipDelta;
		this.failureDelta = builder.failureDelta;
		this.missDelta = builder.missDelta;
		this.pushPull = builder.pushPull;
		this.pickStrategy = builder.pickStrategy;
		this.enableMulticast = builder.enableMulticast;
		this.multicastParam = builder.multicastParam;
		this.multicastMaxWait = builder.multicastMaxWait;
		this.expectedCrashes = expectedCrashes;
		this.reportedCrashed = new ArrayList<>();
		this.start = null;
		this.stop = null;
	}

	public int getDuration() {
		return duration;
	}

	public long getGossipDelta() {
		return gossipDelta;
	}

	public long getFailureDelta() {
		return failureDelta;
	}

	public long getMissDelta() {
		return missDelta;
	}

	public boolean isPushPull() {
		return pushPull;
	}

	public int getPickStrategy() {
		return pickStrategy;
	}

	public boolean isEnableMulticast() {
		return enableMulticast;
	}

	public double getMulticastParam() {
		return multicastParam;
	}

	public int getMulticastMaxWait() {
		return multicastMaxWait;
	}

	public List<ExpectedCrash> getExpectedCrashes() {
		return expectedCrashes;
	}

	/**
	 * Start this experiment.
	 */
	public void start() {
		if (start != null) {
			throw new IllegalStateException("Please call the start() method only once per experiment.");
		}
		start = System.currentTimeMillis();
	}

	/**
	 * Stop this experiment.
	 */
	public void stop() {
		if (stop != null) {
			throw new IllegalStateException("Please call the stop() method only once per experiment.");
		}
		stop = System.currentTimeMillis();
	}

	/**
	 * Report a new crash.
	 *
	 * @param node     Crashed node.
	 * @param reporter Node that reported the crash.
	 */
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
			.add("seed", seed)
			.add("repetition", repetition)
			.add("settings", Json.createObjectBuilder()
				.add("simulate_catastrophe", simulateCatastrophe)
				.add("number_of_nodes", numberOfNodes)
				.add("duration", duration)
				.add("gossip_delta", gossipDelta)
				.add("failure_delta", failureDelta)
				.add("miss_delta", missDelta)
				.add("push_pull", pushPull)
				.add("pick_strategy", pickStrategy)
				.add("enable_multicast", enableMulticast)
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
		return String.format("seed=%d, repetition=%d, simulate_catastrophe=%s, expected_crashes=%d, nodes=%d, " +
				"duration=%dms, gossip_delta=%d, failure_delta=%d, miss_delta=%s, push_pull=%s, pick_strategy=%s, " +
				"enable_multicast=%s, multicast_parameter=%f, multicast_max_wait=%s",
			seed, repetition, simulateCatastrophe, expectedCrashes.size(), numberOfNodes,
			duration, gossipDelta, failureDelta, missDelta, pushPull, pickStrategy,
			enableMulticast, multicastParam, multicastMaxWait);
	}


	// builder to construct a new experiment
	public static final class Builder {
		private Set<String> nodes;
		private Integer seed;
		private Integer repetition;
		private Boolean simulateCatastrophe;
		private Integer duration;
		private Long gossipDelta;
		private Long failureDelta;
		private Long missDelta;
		private Boolean pushPull;
		private Integer pickStrategy;
		private Boolean enableMulticast;
		private Double multicastParam;
		private Integer multicastMaxWait;

		public Builder() {
		}

		public Builder nodes(@NotNull Set<String> nodes) {
			this.nodes = nodes;
			return this;
		}

		public Builder seed(int seed) {
			this.seed = seed;
			return this;
		}

		public Builder repetition(int repetition) {
			this.repetition = repetition;
			return this;
		}

		public Builder simulateCatastrophe(boolean simulateCatastrophe) {
			this.simulateCatastrophe = simulateCatastrophe;
			return this;
		}

		public Builder duration(int duration) {
			this.duration = duration;
			return this;
		}

		public Builder gossipDelta(long gossipDelta) {
			this.gossipDelta = gossipDelta;
			return this;
		}

		public Builder failureDelta(long failureDelta) {
			this.failureDelta = failureDelta;
			return this;
		}

		public Builder missDelta(long missDelta) {
			this.missDelta = missDelta;
			return this;
		}

		public Builder pushPull(boolean pushPull) {
			this.pushPull = pushPull;
			return this;
		}

		public Builder pickStrategy(int pickStrategy) {
			this.pickStrategy = pickStrategy;
			return this;
		}

		public Builder enableMulticast(boolean enableMulticast) {
			this.enableMulticast = enableMulticast;
			return this;
		}

		public Builder multicastParam(double multicastParam) {
			this.multicastParam = multicastParam;
			return this;
		}

		public Builder multicastMaxWait(int multicastMaxWait) {
			this.multicastMaxWait = multicastMaxWait;
			return this;
		}

		public Experiment build() {
			Objects.requireNonNull(nodes);
			Objects.requireNonNull(seed);
			Objects.requireNonNull(repetition);
			Objects.requireNonNull(simulateCatastrophe);
			Objects.requireNonNull(duration);
			Objects.requireNonNull(gossipDelta);
			Objects.requireNonNull(failureDelta);
			Objects.requireNonNull(missDelta);
			Objects.requireNonNull(pushPull);
			Objects.requireNonNull(pickStrategy);
			Objects.requireNonNull(enableMulticast);
			Objects.requireNonNull(multicastParam);
			Objects.requireNonNull(multicastMaxWait);
			return new Experiment(this);
		}
	}
}
