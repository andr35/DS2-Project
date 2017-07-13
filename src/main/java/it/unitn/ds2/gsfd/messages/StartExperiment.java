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
public final class StartExperiment implements Serializable {

	/**
	 * Create a new StartExperiment message for a correct node.
	 * TODO: docs
	 *
	 * @param nodes List of nodes in the system.
	 * @return StartExperiment message.
	 */
	public static StartExperiment normal(boolean pullByGossip, @NotNull Collection<ActorRef> nodes,
										 long gossipTime, long failTime,
										 boolean multicastActive, double multicastParam, int multicastMaxWait,
										 int pickStrategy) {
		return new StartExperiment(pullByGossip, null, nodes, gossipTime, failTime,
			multicastActive, multicastParam, multicastMaxWait, pickStrategy);
	}

	/**
	 * Create a new StartExperiment message for a fault node.
	 * TODO: docs
	 *
	 * @param delta Delay (in milliseconds) after which the node should simulate a crash.
	 * @param nodes List of nodes in the system.
	 * @return StartExperiment message.
	 */
	public static StartExperiment crash(boolean pullByGossip, long delta, @NotNull Collection<ActorRef> nodes,
										long gossipTime, long failTime,
										boolean multicastActive, double multicastParam, int multicastMaxWait,
										int pickStrategy) {
		return new StartExperiment(pullByGossip, delta, nodes, gossipTime, failTime,
			multicastActive, multicastParam, multicastMaxWait, pickStrategy);
	}

	// gossip strategy to use: pull vs push-pull
	private final boolean pullByGossip;

	// time after which to simulate a crash
	private final Long delta;
	private final long gossipTime;
	private final long failTime;
	private final boolean multicastActive;
	private final double multicastParam;
	private final int multicastMaxWait;
	private final int pickStrategy;
	private final List<ActorRef> nodes;

	/**
	 * Create a new StartExperiment message.
	 * TODO: docs
	 *
	 * @param delta If null, the node will not simulate a crash.
	 *              If not null, the node will simulate a crash after delta milliseconds.
	 * @param nodes List of nodes in the system.
	 */
	private StartExperiment(boolean pullByGossip, @Nullable Long delta, @NotNull Collection<ActorRef> nodes,
							long gossipTime, long failTime,
							boolean multicastActive, double multicastParam, int multicastMaxWait,
							int pickStrategy) {

		this.pullByGossip = pullByGossip;
		this.delta = delta;
		this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
		this.gossipTime = gossipTime;
		this.failTime = failTime;
		this.multicastActive = multicastActive;
		this.multicastParam = multicastParam;
		this.multicastMaxWait = multicastMaxWait;
		this.pickStrategy = pickStrategy;
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

	public boolean isMulticastActive() {
		return multicastActive;
	}

	public double getMulticastParam() {
		return multicastParam;
	}

	public int getMulticastMaxWait() {
		return multicastMaxWait;
	}

	public int getPickStrategy() {
		return pickStrategy;
	}

	/**
	 * @return List of node actors in the system.
	 */
	public List<ActorRef> getNodes() {
		return nodes;
	}
}
