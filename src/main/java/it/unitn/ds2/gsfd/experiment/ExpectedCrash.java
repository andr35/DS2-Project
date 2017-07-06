package it.unitn.ds2.gsfd.experiment;

public final class ExpectedCrash {
	private final long delta;
	private final String node;

	ExpectedCrash(long delta, String node) {
		this.delta = delta;
		this.node = node;
	}

	public long getDelta() {
		return delta;
	}

	public String getNode() {
		return node;
	}
}
