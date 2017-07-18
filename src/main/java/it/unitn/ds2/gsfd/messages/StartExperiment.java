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
public final class StartExperiment implements Serializable {

	// nodes that participate in the experiment
	private final List<ActorRef> nodes;

	// time after which to simulate a crash
	private final Long simulateCrashAtDelta;

	// frequency of Gossip
	private final long gossipDelta;

	// time to consider a node failed
	private final long failureDelta;

	// time to consider a missing node failed (enableMulticast recovery)
	private final long missDelta;

	// gossip strategy to use: push vs push-pull
	private final boolean pushPull;

	// indicates what probability distribution is used when choosing random nodes
	private final int pickStrategy;

	// if true, multicast will be periodically issued
	private final boolean enableMulticast;

	// parameter "a" of probability of multicast (enableMulticast recovery)
	private final Double multicastParam;

	// maximum number of times a multicast can be postponed (enableMulticast recovery)
	private final Long multicastMaxWait;


	// private constructor -> use the builder
	private StartExperiment(Builder builder) {
		nodes = Collections.unmodifiableList(new ArrayList<>(builder.nodes));
		simulateCrashAtDelta = builder.simulateCrashAtDelta;
		gossipDelta = builder.gossipDelta;
		failureDelta = builder.failureDelta;
		missDelta = builder.missDelta;
		pushPull = builder.pushPull;
		pickStrategy = builder.pickStrategy;
		enableMulticast = builder.enableMulticast;
		multicastParam = builder.multicastParam;
		multicastMaxWait = builder.multicastMaxWait;
	}

	public List<ActorRef> getNodes() {
		return nodes;
	}

	/**
	 * @return Null if the node should not simulate a crash,
	 * otherwise delay after which to simulate a crash.
	 */
	public Long getSimulateCrashAtDelta() {
		return simulateCrashAtDelta;
	}

	public long getGossipDelta() {
		return gossipDelta;
	}

	public long getFailureDelta() {
		return failureDelta;
	}

	public long getMissDelta() {
		return missDelta;
	}

	public boolean isPushPull() {
		return pushPull;
	}

	public int getPickStrategy() {
		return pickStrategy;
	}

	public boolean enableMulticast() {
		return enableMulticast;
	}

	public double getMulticastParam() {
		if (!enableMulticast) {
			throw new IllegalStateException("The node should not use the multicast parameter if multicast is not enabled");
		}
		return multicastParam;
	}

	public long getMulticastMaxWait() {
		if (!enableMulticast) {
			throw new IllegalStateException("The node should not use the multicast parameter if multicast is not enabled");
		}
		return multicastMaxWait;
	}

	// builder
	public static final class Builder {
		private Collection<ActorRef> nodes;
		private Long simulateCrashAtDelta;
		private Long gossipDelta;
		private Long failureDelta;
		private Long missDelta;
		private Boolean pushPull;
		private Integer pickStrategy;
		private Boolean enableMulticast;
		private Double multicastParam;
		private Long multicastMaxWait;

		public Builder() {
		}

		public Builder nodes(@NotNull Collection<ActorRef> nodes) {
			this.nodes = nodes;
			return this;
		}

		public Builder simulateCrashAtDelta(@Nullable Long simulateCrashAtDelta) {
			this.simulateCrashAtDelta = simulateCrashAtDelta;
			return this;
		}

		public Builder gossipDelta(long gossipDelta) {
			this.gossipDelta = gossipDelta;
			return this;
		}

		public Builder failureDelta(long failureDelta) {
			this.failureDelta = failureDelta;
			return this;
		}

		public Builder missDelta(long missDelta) {
			this.missDelta = missDelta;
			return this;
		}

		public Builder pushPull(boolean pushPull) {
			this.pushPull = pushPull;
			return this;
		}

		public Builder pickStrategy(int pickStrategy) {
			this.pickStrategy = pickStrategy;
			return this;
		}

		public Builder enableMulticast(boolean enableMulticast) {
			this.enableMulticast = enableMulticast;
			return this;
		}

		public Builder multicastParam(Double multicastParam) {
			this.multicastParam = multicastParam;
			return this;
		}

		public Builder multicastMaxWait(Long multicastMaxWait) {
			this.multicastMaxWait = multicastMaxWait;
			return this;
		}

		public StartExperiment build() {
			Objects.requireNonNull(nodes);
			Objects.requireNonNull(gossipDelta);
			Objects.requireNonNull(failureDelta);
			Objects.requireNonNull(missDelta);
			Objects.requireNonNull(pushPull);
			Objects.requireNonNull(pickStrategy);
			Objects.requireNonNull(enableMulticast);
			if (enableMulticast) {
				Objects.requireNonNull(multicastParam);
				Objects.requireNonNull(multicastMaxWait);
			}
			return new StartExperiment(this);
		}
	}
}
