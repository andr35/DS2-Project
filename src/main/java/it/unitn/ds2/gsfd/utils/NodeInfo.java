package it.unitn.ds2.gsfd.utils;

import akka.actor.Cancellable;
import org.jetbrains.annotations.NotNull;

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

	int getQuiescence() {
		return quiescence;
	}

	public void resetQuiescence() {
		quiescence = 0;
	}

	public void heartbeat() {
		beatCount++;
	}

	public void setTimeout(@NotNull Cancellable timeout) {
		if (this.timeout != null) {
			throw new java.lang.RuntimeException("Cannot set timeout while another is active.");
		}

		this.timeout = timeout;
	}

	void cancelTimeout() {
		if (timeout != null) timeout.cancel();
	}

	public void resetTimeout(@NotNull Cancellable timeout) {
		cancelTimeout();
		timeoutId++;
		this.timeout = timeout;
	}

	public long getTimeoutId() {
		return timeoutId;
	}
}
