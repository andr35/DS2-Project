package it.unitn.ds2.gsfd.messages;

import akka.actor.ActorRef;

import java.io.Serializable;

/**
 * Message used to report a node thought to be dead to be alive again to the Tracker.
 */
public final class ReappearReport implements Serializable {

	// crashed node
	private final ActorRef node;

	public ReappearReport(ActorRef node) {
		this.node = node;
	}

	public ActorRef getNode() {
		return node;
	}
}
