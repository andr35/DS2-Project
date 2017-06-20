package it.unitn.ds2.gsfd.messages;

import akka.actor.ActorRef;

/**
 * Message used to report a crash to the Tracker.
 */
public final class ReportCrash {

	// crashed node
	private final ActorRef node;

	public ReportCrash(ActorRef node) {
		this.node = node;
	}

	public ActorRef getNode() {
		return node;
	}
}
