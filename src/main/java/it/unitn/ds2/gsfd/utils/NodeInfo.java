package it.unitn.ds2.gsfd.utils;

import akka.actor.Cancellable;
import scala.concurrent.duration.Duration;

import java.util.concurrent.TimeUnit;

/**
 * Class to manage the beat count and timeout for a node
 */
public class NodeInfo {
	private long beatCount;
	private Cancellable failTimeout;
	private int failId;

	public NodeInfo() {
		beatCount = 0;
		failId = 0;
		failTimeout = null;
	}

	public NodeInfo(Cancellable timeout) {
		beatCount = 0;
		failId = 0;
		failTimeout = timeout;
	}

	public long getBeatCount() {
		return beatCount;
	}

	public void setBeatCount(long beatCount) {
		this.beatCount = beatCount;
	}

	public void heartbeat() {
		if (beatCount == Integer.MAX_VALUE) beatCount = 0;
		else beatCount++;
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

	public void cancelTimeout() {
		failTimeout.cancel();
	}

	public boolean isValid(int beatCount) {
		return this.beatCount < beatCount;
	}
}
