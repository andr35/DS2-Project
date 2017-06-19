package it.unitn.ds2.gsfd;

/**
 * Contains common settings for both the Main an the Tracker.
 */
public final class SystemConstants {

	/**
	 * Unique name for the Akka application.
	 * Used by Akka to contact the other Nodes of the system.
	 */
	public static final String SYSTEM_NAME = "ds2project";

	/**
	 * Unique name for the Akka Main Actor.
	 * Used by Akka to contact the other Nodes of the system.
	 */
	public static final String ACTOR_NAME = "node";

}
