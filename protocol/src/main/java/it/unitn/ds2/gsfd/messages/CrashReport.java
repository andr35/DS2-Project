package it.unitn.ds2.gsfd.messages;

import akka.actor.ActorRef;

import java.io.Serializable;

/**
 * Message used to report a crash to the Tracker.
 */
public final class CrashReport implements Serializable {

	// crashed node
	private final ActorRef node;

	public CrashReport(ActorRef node) {
		this.node = node;
	}

	public ActorRef getNode() {
		return node;
	}
}
