package it.unitn.ds2.gsfd.utils;

import akka.actor.Cancellable;

/**
 * Class to manage the beat count and timeout for a node.
 */
public final class NodeInfo {
	private long beatCount;
	private Cancellable failTimeout;
	private int failId;
	private int quiescence;

	NodeInfo() {
		beatCount = 0;
		failId = 0;
		failTimeout = null;
		quiescence = 0;
	}

	public NodeInfo(Cancellable timeout) {
		beatCount = 0;
		failId = 0;
		failTimeout = timeout;
		quiescence = 0;
	}

	public long getBeatCount() {
		return beatCount;
	}

	public void setBeatCount(long beatCount) {
		this.beatCount = beatCount;
		quiescence = 0;
	}

	public void quiescent() {
		quiescence++;
	}

	public int getQuiescence() {
		return quiescence;
	}

	public void resetQuiescence() {
		quiescence = 0;
	}

	public void heartbeat() {
		beatCount++;
	}

	public void resetTimeout(Cancellable timeout) {
		if (failTimeout == null) return;
		failTimeout.cancel();
		failId++;
		failTimeout = timeout;
	}

	public int getFailId() {
		return failId;
	}
}
