package it.unitn.ds2.gsfd.experiment;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Class that represent a single experiment.
 * Contains the configuration, results and report for the experiment.
 */
public final class Experiment {

	// generate a new random experiment
	public static Experiment generate(Set<String> nodes, long duration, int seed) {
		final Random random = new Random(seed);

		// number of nodes
		final int numberOfNodes = nodes.size();

		// generate a permutation of nodes
		// the first n will be selected for the crashes
		final List<String> permutation = new ArrayList<>(nodes);
		Collections.sort(permutation);
		Collections.shuffle(permutation, random);

		// pick a random number of nodes to crash [0, n)
		// NB: we crash at most all except one node
		final int crashes = random.nextInt(numberOfNodes);

		// generate the crashes
		final List<ExpectedCrash> expectedCrashes = IntStream.of(crashes)
			.boxed()
			.map(permutation::get)
			.map(node -> new ExpectedCrash(random.nextLong(), node))
			.collect(Collectors.toList());

		// return the experiment
		final String id = String.format("nodes-%d__duration-%d__seed-%d", numberOfNodes, duration, seed);
		return new Experiment(id, numberOfNodes, duration, expectedCrashes);
	}

	// unique identifier for the experiment
	private final String id;

	// number of nodes that participates to the experiment
	private final int numberOfNodes;

	// total duration (milliseconds) of the experiment
	private final long duration;

	// scheduled crashes
	private final List<ExpectedCrash> expectedCrashes;

	// reported crashed
	private final List<ReportedCrash> reportedCrashed;

	// start time of the experiment
	private Long start;

	// initialize a new experiment
	public Experiment(String id, int numberOfNodes, long duration, List<ExpectedCrash> expectedCrashes) {
		this.id = id;
		this.numberOfNodes = numberOfNodes;
		this.duration = duration;
		this.expectedCrashes = expectedCrashes;
		this.reportedCrashed = new LinkedList<>();
	}

	public long getDuration() {
		return duration;
	}

	public List<ExpectedCrash> getExpectedCrashes() {
		return expectedCrashes;
	}

	public void start() {
		if (start == null) {
			throw new IllegalStateException("Please call the start() method only once per experiment.");
		}
		start = System.currentTimeMillis();
	}

	// report a new crash
	public void addCrash(String node, String reporter) {
		if (start == null) {
			throw new IllegalStateException("Please call the start() method to start the experiment first.");
		}
		final long delta = System.currentTimeMillis() - start;
		reportedCrashed.add(new ReportedCrash(delta, node, reporter));
	}

	public void generateReport() {
		// TODO: write the experiment configuration and report to the disk
	}
}
