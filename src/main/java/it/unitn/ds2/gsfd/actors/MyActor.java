package it.unitn.ds2.gsfd.actors;

import akka.actor.ActorRef;

/**
 * TODO
 */
public interface MyActor {

	default String idFromRef(ActorRef ref) {
		return ref.path().name();
	}
}
