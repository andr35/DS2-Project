package it.unitn.ds2.gsfd.messages;

import akka.actor.ActorRef;

import java.io.Serializable;

/**
 * Message used to report a crash to the Tracker.
 */
public final class ReportCrash implements Serializable {

	// crashed node
	private final ActorRef node;

	public ReportCrash(ActorRef node) {
		this.node = node;
	}

	public ActorRef getNode() {
		return node;
	}
}
