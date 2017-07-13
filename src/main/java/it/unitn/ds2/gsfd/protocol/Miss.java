package it.unitn.ds2.gsfd.protocol;

import akka.actor.ActorRef;

import java.io.Serializable;

/**
 * Message a node sends to self to detect a failed node in catastrophic events.
 */
public final class Miss implements Serializable{
		private final ActorRef missing;
		private final long missId;

		public Miss(ActorRef missing, long missId) {
			this.missing = missing;
			this.missId = missId;
		}

		public ActorRef getMissing() {
			return missing;
		}

		public long getMissId() {
			return missId;
		}
}

