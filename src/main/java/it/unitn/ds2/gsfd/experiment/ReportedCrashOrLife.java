package it.unitn.ds2.gsfd.experiment;

final class ReportedCrashOrLife {
	private final long delta;
	private final String node;
	private final String reporter;

	ReportedCrashOrLife(long delta, String node, String reporter) {
		this.delta = delta;
		this.node = node;
		this.reporter = reporter;
	}

	public long getDelta() {
		return delta;
	}

	public String getNode() {
		return node;
	}

	public String getReporter() {
		return reporter;
	}
}
