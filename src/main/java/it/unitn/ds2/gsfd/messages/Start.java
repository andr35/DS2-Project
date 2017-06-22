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
	public static Start normal(@NotNull Collection<ActorRef> nodes,
							   @NotNull Long gossipTime, @NotNull Long failTime,
							   @NotNull Double multicastParam, @NotNull Integer multicastMaxWait) {
		return new Start(null, nodes, gossipTime, failTime, multicastParam, multicastMaxWait);
	}

	/**
	 * Create a new Start message for a fault node.
	 *
	 * @param delta Delay (in milliseconds) after which the node should simulate a crash.
	 * @param nodes List of nodes in the system.
	 * @return Start message.
	 */
	public static Start crash(@NotNull Long delta, @NotNull Collection<ActorRef> nodes,
							  @NotNull Long gossipTime, @NotNull Long failTime,
							  @NotNull Double multicastParam, @NotNull Integer multicastMaxWait) {
		return new Start(delta, nodes, gossipTime, failTime, multicastParam, multicastMaxWait);
	}

	// time after which to simulate a crash
	private final Long delta;
	private final Long gossipTime;
	private final Long failTime;
	private final Double multicastParam;
	private final Integer multicastMaxWait;
	private final List<ActorRef> nodes;

	/**
	 * Create a new Start message.
	 *
	 * @param delta If null, the node will not simulate a crash.
	 *              If not null, the node will simulate a crash after delta milliseconds.
	 * @param nodes List of nodes in the system.
	 */
	private Start(@Nullable Long delta, @NotNull Collection<ActorRef> nodes,
				  @NotNull Long gossipTime, @NotNull Long failTime,
				  @NotNull Double multicastParam, @NotNull Integer multicastMaxWait) {
		this.delta = delta;
		this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
		this.gossipTime = gossipTime;
		this.failTime = failTime;
		this.multicastParam = multicastParam;
		this.multicastMaxWait = multicastMaxWait;
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
	public Long getDelta() {
		return delta;
	}

	public Long getGossipTime() {
		return gossipTime;
	}

	public Long getFailTime() {
		return failTime;
	}

	public Double getMulticastParam() {
		return multicastParam;
	}

	public Integer getMulticastMaxWait() {
		return multicastMaxWait;
	}

	/**
	 * @return List of node actors in the system.
	 */
	public List<ActorRef> getNodes() {
		return nodes;
	}
}
