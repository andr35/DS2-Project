package it.unitn.ds2.gsfd.experiment;

public class ExpectedCrash {
	private final long delta;
	private final String node;

	public ExpectedCrash(long delta, String node) {
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
