package it.unitn.ds2.gsfd.messages;

import akka.actor.ActorRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Message used to start a new experiment.
 * The tracker sends this to all nodes and tells each node
 * whether it should crash at some point or not.
 */
public final class Start implements Serializable {

	/**
	 * Create a new Start message for a correct node.
	 *
	 * @param nodes List of nodes in the system.
	 * @return Start message.
	 */
	public static Start normal(@NotNull Collection<ActorRef> nodes) {
		return new Start(null, nodes);
	}

	/**
	 * Create a new Start message for a fault node.
	 *
	 * @param delta Delay (in milliseconds) after which the node should simulate a crash.
	 * @param nodes List of nodes in the system.
	 * @return Start message.
	 */
	public static Start crash(@NotNull Long delta, @NotNull Collection<ActorRef> nodes) {
		return new Start(delta, nodes);
	}

	// time after which to simulate a crash
	private final Long delta;
	private final List<ActorRef> nodes;

	/**
	 * Create a new Start message.
	 *
	 * @param delta If null, the node will not simulate a crash.
	 *              If not null, the node will simulate a crash after delta milliseconds.
	 * @param nodes List of nodes in the system.
	 */
	private Start(@Nullable Long delta, @NotNull Collection<ActorRef> nodes) {
		this.delta = delta;
		this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
	}

	/**
	 * @return True if the node should simulate a crash.
	 */
	public boolean isFaulty() {
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
