package it.unitn.ds2.gsfd.messages;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * Message used to start a new experiment.
 * The tracker sends this to all nodes and tells each node
 * whether it should crash at some point or not.
 */
public final class Start implements Serializable {

	/**
	 * Create a new Start message for a correct node.
	 *
	 * @return Start message.
	 */
	public static Start normal() {
		return new Start(null);
	}

	/**
	 * Create a new Start message for a fault node.
	 *
	 * @param delta Delay (in milliseconds) after which the node should simulate a crash.
	 * @return Start message.
	 */
	public static Start crash(long delta) {
		return new Start(delta);
	}

	// time after which to simulate a crash
	private final Long delta;

	/**
	 * Create a new Start message.
	 *
	 * @param delta If null, the node will not simulate a crash.
	 *              If not null, the node will simulate a crash after delta milliseconds.
	 */
	private Start(@Nullable Long delta) {
		this.delta = delta;
	}

	/**
	 * @return True if the node should simulate a crash.
	 */
	public boolean simulateCrash() {
		return delta != null;
	}

	/**
	 * @return Null if the node should not simulate a crash,
	 * otherwise delay after which to simulate a crash.
	 */
	@Nullable
	public Long detla() {
		return delta;
	}
}
