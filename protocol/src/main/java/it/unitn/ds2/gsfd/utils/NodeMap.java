package it.unitn.ds2.gsfd.utils;

import akka.actor.ActorRef;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Class to manage the HashMap of all nodes.
 */
public final class NodeMap {

	// owner of this view of the system
	private ActorRef primary;

	// all nodes with their information
	private Map<ActorRef, NodeInfo> nodes;

	// nodes considered correct
	// these can be updated and gossiped to
	private Set<ActorRef> correctNodes;

	// nodes that may be missing (catastrophe reaction)
	// these can be updated but not gossiped to
	private Set<ActorRef> missingNodes;

	public NodeMap(List<ActorRef> nodes, ActorRef primary) {
		this.primary = primary;
		this.nodes = new HashMap<>();
		correctNodes = new HashSet<>(nodes);
		correctNodes.remove(this.primary);
		nodes.forEach(ref -> this.nodes.put(ref, new NodeInfo()));
		missingNodes = new HashSet<>();
	}

	public void clear() {
		primary = null;
		correctNodes.clear();
		missingNodes.clear();
		nodes.forEach((ref, info) -> info.cancelTimeout());
		nodes.clear();
	}

	public NodeInfo get(ActorRef ref) {
		return nodes.get(ref);
	}

	public void reappear(ActorRef ref) {
		nodes.put(ref, new NodeInfo());
		correctNodes.add(ref);
	}

	public NodeInfo remove(ActorRef ref) {
		correctNodes.remove(ref);
		missingNodes.remove(ref);
		return nodes.remove(ref);
	}

	public void setFailed(ActorRef ref) {
		missingNodes.remove(ref);
		correctNodes.remove(ref);
	}

	public void setMissing(ActorRef ref) {
		if (correctNodes.contains(ref)) {
			correctNodes.remove(ref);
			missingNodes.add(ref);
		}
	}

	public void unsetMissing(ActorRef ref) {
		if (missingNodes.contains(ref)) {
			correctNodes.add(ref);
			missingNodes.remove(ref);
		}
	}

	public Set<ActorRef> getAllNodes() {
		return new HashSet<>(nodes.keySet());
	}

	public Set<ActorRef> getCorrectNodes() {
		return Collections.unmodifiableSet(correctNodes);
	}

	public Set<ActorRef> getUpdatableNodes() {

		// include all nodes that are correct or missing
		Set<ActorRef> updatables = new HashSet<>(correctNodes);
		updatables.addAll(missingNodes);
		return updatables;
	}

	/**
	 * Function to create the structure for heartbeat counters to be attached
	 * to gossip and multicast messages.
	 * Does not contain nodes considered failed (those waiting for cleanup).
	 *
	 * @return Map of nodes into corresponding heartbeat counter.
	 */
	public Map<ActorRef, Long> getBeats() {
		Map<ActorRef, Long> updatableBeats = new HashMap<>();

		// include heartbeats of all nodes that are correct or missing
		Set<ActorRef> updatables = new HashSet<>(correctNodes);
		updatables.addAll(missingNodes);
		updatables.forEach(ref -> updatableBeats.put(ref, nodes.get(ref).getBeatCount()));

		// include self
		updatableBeats.put(primary, nodes.get(primary).getBeatCount());

		return updatableBeats;
	}

	/**
	 * Randomly selects one node among those that are correct.
	 *
	 * @param strategy indicates how the random node should be
	 *                 chosen.
	 *                 0: uniform random;
	 *                 1: based on quiescence;
	 *                 2: based on quiescence, squared.
	 * @return Akka's ActorRef of the chosen node.
	 */
	@Nullable
	public ActorRef pickNode(int strategy) {

		// quiescence debug -----------------
		String s = "QUIESCENCE : {";
		for (ActorRef c : correctNodes) {
			s += " (" + c.path().name() + ", Q=" + nodes.get(c).getQuiescence() + ") ";
		}
		s += "}";
		System.out.println(s);
		//-----------------------------------

		if (correctNodes.isEmpty()) {
			return null;
		}

		final List<ActorRef> candidates = new ArrayList<>(correctNodes);

		if (strategy < 0 || strategy > 3) {
			throw new RuntimeException("pickNode strategy unexpected (" + strategy + ")");
		}

		// with strategy 0 all nodes have the same probability
		// we can directly return a random node
		if (strategy == 0) {
			final Random r = new Random();
			final int randomIndex = r.nextInt(candidates.size());
			return candidates.get(randomIndex);
		}

		// with strategy 3 always choose a random node with highest quiescence
		if (strategy == 3) {
			long maxQuiescence = nodes.get(candidates.get(0)).getQuiescence();
			List<ActorRef> maxRef = new ArrayList<>();
			maxRef.add(candidates.get(0));

			for (int i = 1; i < candidates.size(); i++) {
				final long q = nodes.get(candidates.get(i)).getQuiescence();
				if (q > maxQuiescence) {
					maxQuiescence = q;
					maxRef.clear();
					maxRef.add(candidates.get(i));
				}
				if (q == maxQuiescence) {
					maxRef.add(candidates.get(i));
				}
			}

			final Random r = new Random();
			final int randomIndex = r.nextInt(maxRef.size());
			return maxRef.get(randomIndex);
		}

		// for strategies 1 and 2, compute in advance a score for each node
		// the +1 guarantees every node has a chance to be chosen
		final List<Long> nodeScores = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			if (strategy == 1) {
				nodeScores.add(i, nodes.get(candidates.get(i)).getQuiescence() + 1);
			} else if (strategy == 2) {
				nodeScores.add(i, (long) Math.pow(nodes.get(candidates.get(i)).getQuiescence(), 2) + 1);
			}

		}

		// generate a random number whose value ranges from 0.0 to the sum
		// of the values of the function specified above
		double randomMultiplier = 0;
		for (int i = 0; i < candidates.size(); i++) {
			randomMultiplier += nodeScores.get(i);
		}
		final Random r = new Random();
		double randomDouble = r.nextDouble() * randomMultiplier;

		// subtract function in terms of i to the total until negative value is reached
		// the corresponding iteration is the random number chosen
		int k;
		for (k = 0; k < candidates.size(); k++) {
			randomDouble -= nodeScores.get(k);
			if (randomDouble <= 0) {
				return candidates.get(k);
			}
		}

		return candidates.get(k);
	}
}
