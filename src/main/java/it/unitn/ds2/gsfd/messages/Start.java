package it.unitn.ds2.gsfd.messages;

import akka.actor.ActorRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.*;

/**
 * Message used to start a new experiment.
 * The tracker sends this to all nodes and tells each node
 * whether it should crash at some point or not.
 */
public final class Start implements Serializable {

	/**
	 * Create a new Start message for a correct node.
	 * TODO: docs
	 *
	 * @param nodes List of nodes in the system.
	 * @return Start message.
	 */
	public static Start normal(boolean pullByGossip, @NotNull Collection<ActorRef> nodes,
							   @NotNull Long gossipTime, @NotNull Long failTime,
							   @NotNull Double multicastParam, @NotNull Integer multicastMaxWait) {
		return new Start(pullByGossip, null, nodes, gossipTime, failTime, multicastParam, multicastMaxWait);
	}

	/**
	 * Create a new Start message for a fault node.
	 * TODO: docs
	 *
	 * @param delta Delay (in milliseconds) after which the node should simulate a crash.
	 * @param nodes List of nodes in the system.
	 * @return Start message.
	 */
	public static Start crash(boolean pullByGossip, @NotNull Long delta, @NotNull Collection<ActorRef> nodes,
							  @NotNull long gossipTime, @NotNull long failTime,
							  @NotNull double multicastParam, @NotNull int multicastMaxWait) {
		return new Start(pullByGossip, delta, nodes, gossipTime, failTime, multicastParam, multicastMaxWait);
	}

	// gossip strategy to use: pull vs push-pull
	private final boolean pullByGossip;

	// time after which to simulate a crash
	private final Long delta;
	private final long gossipTime;
	private final long failTime;
	private final double multicastParam;
	private final int multicastMaxWait;
	private final List<ActorRef> nodes;

	/**
	 * Create a new Start message.
	 * TODO: docs
	 *
	 * @param delta If null, the node will not simulate a crash.
	 *              If not null, the node will simulate a crash after delta milliseconds.
	 * @param nodes List of nodes in the system.
	 */
	private Start(boolean pullByGossip, @Nullable Long delta, @NotNull Collection<ActorRef> nodes,
				  @NotNull long gossipTime, @NotNull long failTime,
				  @NotNull double multicastParam, @NotNull int multicastMaxWait) {
		this.pullByGossip = pullByGossip;
		this.delta = delta;
		this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
		this.gossipTime = gossipTime;
		this.failTime = failTime;
		this.multicastParam = multicastParam;
		this.multicastMaxWait = multicastMaxWait;
	}

	public boolean isPullByGossip() {
		return pullByGossip;
	}

	/**
	 * @return Null if the node should not simulate a crash,
	 * otherwise delay after which to simulate a crash.
	 */
	@Nullable
	public Long getDelta() {
		return delta;
	}

	public long getGossipTime() {
		return gossipTime;
	}

	public long getFailTime() {
		return failTime;
	}

	public double getMulticastParam() {
		return multicastParam;
	}

	public int getMulticastMaxWait() {
		return multicastMaxWait;
	}

	/**
	 * @return List of node actors in the system.
	 */
	public List<ActorRef> getNodes() {
		return nodes;
	}
}
