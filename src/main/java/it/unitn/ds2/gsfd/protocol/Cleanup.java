package it.unitn.ds2.gsfd.protocol;

import akka.actor.ActorRef;

import java.io.Serializable;

/**
 * Message a node sends to self to permanently remove a node.
 */
public final class Cleanup implements Serializable {
	private final ActorRef failed;

	public Cleanup(ActorRef failed) {
		this.failed = failed;
	}

	public ActorRef getFailed() {
		return failed;
	}
}
