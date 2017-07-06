package it.unitn.ds2.gsfd.protocol;

import akka.actor.ActorRef;

import java.io.Serializable;

/**
 * Message a node sends to self to permanently remove a node.
 */
public final class Cleanup implements Serializable {
	private final ActorRef failed;
	private final long cleanId;

	public Cleanup(ActorRef failed, long cleanId) {
		this.failed = failed;
		this.cleanId = cleanId;
	}

	public ActorRef getFailed() {
		return failed;
	}

	public long getCleanId() {
		return cleanId;
	}
}
