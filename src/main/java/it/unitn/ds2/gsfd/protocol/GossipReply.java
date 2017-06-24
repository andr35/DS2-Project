package it.unitn.ds2.gsfd.protocol;

import akka.actor.ActorRef;

import java.io.Serializable;
import java.util.Map;

/**
 * Message to gossip the node's current view
 */
public final class GossipReply implements Serializable {
	private final Map<ActorRef, Long> beats;

	public GossipReply(Map<ActorRef, Long> beats) {
		this.beats = beats;
	}

	public Map<ActorRef, Long> getBeats() {
		return beats;
	}
}
