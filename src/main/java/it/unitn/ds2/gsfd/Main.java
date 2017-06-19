package it.unitn.ds2.gsfd;

import akka.actor.ActorSystem;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import it.unitn.ds2.gsfd.node.NodeActor;
import it.unitn.ds2.gsfd.tracker.TrackerActor;
import org.apache.commons.validator.routines.InetAddressValidator;

/**
 * Entry point to launch a new node of the system or a new tracker.
 */
public final class Main {


	/**
	 * Key used in the configuration file to pass the ID for the Node to launch.
	 */
	private static final String CONFIG_NODE_ID = "node.id";


	/**
	 * Error message to print when the Node is invoked with the wrong parameters.
	 */
	private static final String USAGE = "\n" +
		"Usage: java Node TYPE [ip] [port]\n" +
		"\n" +
		"Launch a new Node in the system.\n" +
		"\n" +
		"Types:\n" +
		"   node  	  Bootstrap a new node in the system\n" +
		"   tracker   Bootstrap a Tracker node (does NOT require ip and port)\n" +
		"\n" +
		"Arguments:\n" +
		"   ip         The IP of the tracker to contact\n" +
		"   port       The TCP port to use to which the tracker is listening\n";

	/**
	 * Print an help message and exit.
	 */
	private static void printHelpAndExit() {
		System.err.println(USAGE);
		System.exit(2);
	}


	/**
	 * Validate IP and port.
	 *
	 * @param ip   IP to validate.
	 * @param port Port to validate.
	 * @return True if both IP and port are valid, false otherwise.
	 */
	private static boolean validateIPAndPort(String ip, String port) {
		try {
			final int portAsInteger = Integer.parseInt(port);
			return InetAddressValidator.getInstance().isValid(ip) &&
				portAsInteger >= 1 && portAsInteger <= 65535;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/**
	 * Entry point.
	 *
	 * @param args Command line arguments.
	 */
	public static void main(String[] args) {

		// check the command line arguments
		if (args.length < 1) {
			printHelpAndExit();
		}

		// extract the type of node
		final String type = args[0];
		switch (type) {

			// bootstrap a new system
			case "tracker": {

				// validate number of arguments
				if (args.length != 1) {
					printHelpAndExit();
				}

				// bootstrap the tracker
				bootstrapTracker();
				break;
			}

			// launch this node and ask it to join an existing system
			case "node": {

				// validate number of arguments
				if (args.length != 3) {
					printHelpAndExit();
				}

				// extract ip and port of the node to contact to join the system
				final String ip = args[1];
				final String port = args[2];
				if (!validateIPAndPort(ip, port)) {
					System.err.println("Invalid IP address or port");
					printHelpAndExit();
				}

				// launch the new Node
				bootstrapNode(ip, port);
				break;
			}

			// command not found
			default: {
				printHelpAndExit();
			}
		}
	}


	/**
	 * Launch a new Node of the system.
	 *
	 * @param ip   IP of the tracker to contact.
	 * @param port Port of the tracker.
	 */
	private static void bootstrapNode(String ip, String port) {

		// load configuration
		final Config config = ConfigFactory.load();

		// initialize Akka
		final ActorSystem system = ActorSystem.create(SystemConstants.SYSTEM_NAME, config);

		// create a NodeActor of type "join" and add it to the system
		final int id = config.getInt(CONFIG_NODE_ID);
		final String trackerAddress = String.format("akka.tcp://%s@%s:%s/user/%s",
			SystemConstants.SYSTEM_NAME, ip, port, SystemConstants.ACTOR_NAME);
		system.actorOf(NodeActor.init(id, trackerAddress), SystemConstants.ACTOR_NAME);
	}

	/**
	 * Launch a new Tracker.
	 */
	private static void bootstrapTracker() {

		// load configuration
		final Config config = ConfigFactory.load();

		// initialize Akka
		final ActorSystem system = ActorSystem.create(SystemConstants.SYSTEM_NAME, config);

		// create a NodeActor of type "join" and add it to the system
		final int id = config.getInt(CONFIG_NODE_ID);
		system.actorOf(TrackerActor.init(id), SystemConstants.ACTOR_NAME);
	}

}
