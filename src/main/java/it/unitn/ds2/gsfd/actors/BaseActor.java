package it.unitn.ds2.gsfd.actors;

import akka.actor.ActorRef;

/**
 * This is the interface for all actors that provides some utilities
 * such as getting the id of an actor from the reference.
 */
public interface BaseActor {

	/**
	 * Extract the ID of the actor from an Akka reference.
	 *
	 * @param reference Akka actor reference.
	 * @return ID of the actor.
	 */
	default String idFromRef(ActorRef reference) {
		return reference.path().name();
	}
}
