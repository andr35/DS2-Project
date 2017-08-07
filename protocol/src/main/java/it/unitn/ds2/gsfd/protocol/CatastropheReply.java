package it.unitn.ds2.gsfd.protocol;

import akka.actor.ActorRef;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

public class CatastropheReply implements Serializable {
	private final Map<ActorRef, Long> beats;

	public CatastropheReply(Map<ActorRef, Long> beats) {
		this.beats = Collections.unmodifiableMap(beats);
	}

	public Map<ActorRef, Long> getBeats() {
		return beats;
	}
}
