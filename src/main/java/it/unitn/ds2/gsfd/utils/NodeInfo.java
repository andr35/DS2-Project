package it.unitn.ds2.gsfd.utils;

import akka.actor.Cancellable;

/**
 * Class to manage the beat count and timeout for a node.
 */
public final class NodeInfo {
	private long beatCount;
	private Cancellable timeout;
	private long timeoutId;
	private int quiescence;

	NodeInfo() {
		beatCount = 0;
		timeoutId = 0;
		timeout = null;
		quiescence = 0;
	}

	public NodeInfo(Cancellable timeout) {
		beatCount = 0;
		timeoutId = 0;
		this.timeout = timeout;
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

	public void cancelTimeout() {
		if (timeout != null) timeout.cancel();
	}

	public void resetTimeout(Cancellable timeout) {
		if (timeout == null || this.timeout == null) return;
		this.timeout.cancel();
		timeoutId++;
		this.timeout = timeout;
	}

	public long getTimeoutId() {
		return timeoutId;
	}
}
