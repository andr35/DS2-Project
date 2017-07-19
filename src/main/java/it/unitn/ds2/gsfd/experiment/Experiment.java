package it.unitn.ds2.gsfd.experiment;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

	// counter for the experiments -> used to generate the IDs
	private static int COUNTER = 0;


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
	private final Double multicastParam;

	// maximum number of times a multicast can be postponed (enableMulticast recovery)
	private final Long multicastMaxWait;


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
		COUNTER++;

		////////////////////////////////////////////////////////////////////
		// generate the experiment
		////////////////////////////////////////////////////////////////////

		// generate an ID for the experiment
		final String id = String.format("%05d", COUNTER);

		// number of nodes
		final int numberOfNodes = builder.nodes.size();

		// if simulate enableMulticast -> crash 2/3 nodes, else just one
		final int crashes = builder.simulateCatastrophe ? (int) Math.ceil(numberOfNodes * 2.0 / 3.0) : 1;

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
		final List<ExpectedCrash> expectedCrashes = IntStream.range(0, crashes)
			.boxed()
			.map(permutation::get)
			.map(node -> new ExpectedCrash(crashTime, node))
			.collect(Collectors.toList());

		// security check
		if (crashes != expectedCrashes.size()) {
			throw new AssertionError("Bug in the code...");
		}

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

	public boolean enableMulticast() {
		return enableMulticast;
	}

	@Nullable
	public Double getMulticastParam() {
		return multicastParam;
	}

	@Nullable
	public Long getMulticastMaxWait() {
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
				.add("multicast_parameter", multicastParam == null ? JsonValue.NULL : Json.createValue(multicastParam))
				.add("multicast_max_wait", multicastMaxWait == null ? JsonValue.NULL : Json.createValue(multicastMaxWait))
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

	/**
	 * Helper that computes expected time of first multicast for a values
	 * and finds multicastParam (a) that scores the closest time.
	 *
	 * @param nodes                        Number of nodes in the system.
	 * @param maxWaitMillis                Maximum number of milliseconds multicast can be postponed.
	 * @param expectedFirstMulticastMillis Desired time of first multicast (in milliseconds).
	 * @return Value a associated to the most accurate expected first multicast.
	 */
	public static double findMulticastParameter(int nodes, long maxWaitMillis, long expectedFirstMulticastMillis) {

		// conversion
		long maxWaitSeconds = (long) Math.round(maxWaitMillis / 1000);
		final long expectedFirstMulticastSeconds = (long) Math.round(expectedFirstMulticastMillis / 1000);

		double aFirst = 1.0;
		double aLast = 30.0; // maximum a to test, to guarantee termination
		double aStep = 0.25;

		double a = aFirst;

		double aClosest = 0.0;
		double diff = 0.0;

		// compute e, expected time of first multicast, wrt to a values
		while (a <= aLast) {
			double e = 0.0;

			for (double t = 0.0; t <= maxWaitSeconds; t++) {
				double m1 = t / maxWaitSeconds;
				double e1 = t * ((1 - Math.pow(1 - Math.pow(m1, a), nodes)));

				double e2 = 1.0;
				for (double w = 0.0; w <= t - 1; w++) {
					double m2 = w / maxWaitSeconds;
					e2 = e2 * (1 - (1 - Math.pow(1 - Math.pow(m2, a), nodes)));
				}

				e += e1 * e2;
			}

			// as long as we don't surpass required time, a is the last one tried
			if (e <= expectedFirstMulticastSeconds) {
				aClosest = a;
				diff = Math.abs(expectedFirstMulticastSeconds - e);
			}

			// when we surpass, take the closer
			else {

				// last e is closer
				if (diff > Math.abs(expectedFirstMulticastSeconds - e)) {
					return a;
				}

				// previous e was closer
				else {
					return aClosest;
				}
			}

			// next value of a
			a += aStep;
		}

		return aClosest;
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
		private Long multicastMaxWait;

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

		public Builder multicastParam(@Nullable Double multicastParam) {
			this.multicastParam = multicastParam;
			return this;
		}

		public Builder multicastMaxWait(@Nullable Long multicastMaxWait) {
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
			Objects.requireNonNull(pushPull);
			Objects.requireNonNull(pickStrategy);
			Objects.requireNonNull(enableMulticast);
			if (enableMulticast) {
				Objects.requireNonNull(missDelta);
				Objects.requireNonNull(multicastParam);
				Objects.requireNonNull(multicastMaxWait);
			}
			return new Experiment(this);
		}
	}
}
