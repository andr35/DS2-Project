package it.unitn.ds2.gsfd.protocol;

import akka.actor.ActorRef;

import java.io.Serializable;

/**
 * Message a node sends to self to detect a failed node.
 */
public final class Fail implements Serializable {
	private final ActorRef failing;
	private final long failId;

	public Fail(ActorRef failing, long failId) {
		this.failing = failing;
		this.failId = failId;
	}

	public ActorRef getFailing() {
		return failing;
	}

	public long getFailId() {
		return failId;
	}
}
