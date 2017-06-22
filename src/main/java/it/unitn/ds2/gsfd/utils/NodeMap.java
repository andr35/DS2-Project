package it.unitn.ds2.gsfd.utils;

import akka.actor.ActorRef;

import java.util.HashMap;
import java.util.Map;

/**
 * Class to manage the HashMap of all nodes
 */
public class NodeMap extends HashMap<ActorRef, NodeInfo>{

	public NodeMap() {
		super();
	}

	public Map<ActorRef, Long> getBeats() {
		Map<ActorRef, Long> beats = new HashMap<>();
		forEach((ref, info) -> beats.put(ref, info.getBeatCount()));
		return beats;
	}

	public String beatsToString() {
		String result = "{ ";
		for (Map.Entry<ActorRef, NodeInfo> entry : entrySet()) {
			ActorRef ref = entry.getKey();
			NodeInfo info = entry.getValue();
			result += " (" + ref.path().name() + ", " + info.getBeatCount() + ") ";
		}
		return result + "}";
	}

}
