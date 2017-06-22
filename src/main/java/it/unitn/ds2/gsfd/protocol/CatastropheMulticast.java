package it.unitn.ds2.gsfd.protocol;

import akka.actor.ActorRef;

import java.io.Serializable;
import java.util.Map;

/**
 * Message to gossip the node's current view (multicast)
 */
public final class CatastropheMulticast implements Serializable {
	private final Map<ActorRef, Long> beats;

	public CatastropheMulticast(Map<ActorRef, Long> beats) {
		this.beats = beats;
	}

	public Map<ActorRef, Long> getBeats() {
		return beats;
	}
}
