package it.unitn.ds2.gsfd.utils;

import akka.actor.ActorRef;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Class to manage the HashMap of all nodes.
 */
public final class NodeMap extends HashMap<ActorRef, NodeInfo> {

	// owner of this view of the system
	private ActorRef primary;

	// nodes considered correct
	// these can be updated and gossiped to
	private List<ActorRef> correctNodes;

	// nodes that may be missing (catastrophe reaction)
	// these can be updated but not gossiped to
	private List<ActorRef> missingNodes;

	public NodeMap(List<ActorRef> nodes, ActorRef primary) {
		this.primary = primary;
		correctNodes = new ArrayList<>(nodes);
		correctNodes.remove(this.primary);
		nodes.forEach(ref -> put(ref, new NodeInfo()));
		missingNodes = new ArrayList<>();
	}

	@Override
	public void clear() {
		primary = null;
		correctNodes.clear();
		missingNodes.clear();
		forEach((ref, info) -> info.cancelTimeout());
		super.clear();
	}

	public void setFailed(ActorRef ref) {
		missingNodes.remove(ref);
		correctNodes.remove(ref);
	}

	public void setMissing(ActorRef ref) {
		if (!missingNodes.contains(ref))
			missingNodes.add(ref);
	}

	public void unsetMissing(ActorRef ref) {
		missingNodes.remove(ref);
	}

	public List<ActorRef> getCorrectNodes() {
		return Collections.unmodifiableList(correctNodes);
	}

	public List<ActorRef> getUpdatableNodes() {
		List<ActorRef> updatables = new ArrayList<>(correctNodes);
		for (ActorRef ref : missingNodes){
			if (!updatables.contains(ref))
				updatables.add(ref);
		}
		return updatables;
	}

	public Map<ActorRef, Long> getBeats() {
		Map<ActorRef, Long> beats = new HashMap<>();
		forEach((ref, info) -> beats.put(ref, info.getBeatCount()));
		return beats;
	}

	/*public String beatsToString() {
		final StringBuilder result = new StringBuilder("{");
		for (Map.Entry<ActorRef, NodeInfo> entry : entrySet()) {
			ActorRef ref = entry.getKey();
			NodeInfo info = entry.getValue();
			result.append(" (").append(ref.path().name()).append(", ").append(info.getBeatCount()).append(") ");
		}
		return result.toString() + "}";
	}*/

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

		if (correctNodes.isEmpty()) return null;

		if (strategy < 0 || strategy > 2) {
			throw new java.lang.RuntimeException("pickNode strategy unexpected (" + strategy + ")");
		}

		// with strategy 0 all nodes have the same probability
		// we can directly return a random node
		if (strategy == 0) {
			Random r = new Random();
			int randomIndex = r.nextInt(correctNodes.size());
			return correctNodes.get(randomIndex);
		}

		List<ActorRef> candidates = new ArrayList<>(keySet());
		candidates.retainAll(correctNodes);

		// compute in advance a score for each node (will alter probability of it being chosen).
		// the +1 guarantees every node has a chance to be chosen
		// TODO: more complex function (possibly by experiment setting)
		List<Long> nodeScores = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			if (strategy == 1)
				nodeScores.add(i, get(candidates.get(i)).getQuiescence() + 1);
			else if (strategy == 2)
				nodeScores.add(i, (long) Math.pow(get(candidates.get(i)).getQuiescence(), 2) + 1);
		}

		// generate a random number whose value ranges from 0.0 to the sum
		// of the values of the function specified above
		double randomMultiplier = 0;
		for (int i = 0; i < candidates.size(); i++) {
			randomMultiplier += nodeScores.get(i);
		}
		Random r = new Random();
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
