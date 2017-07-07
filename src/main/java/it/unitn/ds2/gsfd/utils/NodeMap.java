package it.unitn.ds2.gsfd.utils;

import akka.actor.ActorRef;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Class to manage the HashMap of all nodes.
 */
public final class NodeMap extends HashMap<ActorRef, NodeInfo> {

	private ActorRef primary;
	// nodes considered correct
	private List<ActorRef> correctNodes;

	public NodeMap(List<ActorRef> nodes, ActorRef primary) {
		this.primary = primary;
		correctNodes = new ArrayList<>(nodes);
		correctNodes.remove(this.primary);
		nodes.forEach(ref -> put(ref, new NodeInfo()));
	}

	@Override
	public void clear() {
		primary = null;
		correctNodes.clear();
		forEach((ref, info) -> info.cancelTimeout());
		super.clear();
	}

	public void setFailed(ActorRef ref) {
		correctNodes.remove(ref);
	}

	public List<ActorRef> getCorrectNodes() {
		return Collections.unmodifiableList(correctNodes);
	}

	public Map<ActorRef, Long> getBeats() {
		Map<ActorRef, Long> beats = new HashMap<>();
		forEach((ref, info) -> beats.put(ref, info.getBeatCount()));
		return beats;
	}

	public String beatsToString() {
		final StringBuilder result = new StringBuilder("{");
		for (Map.Entry<ActorRef, NodeInfo> entry : entrySet()) {
			ActorRef ref = entry.getKey();
			NodeInfo info = entry.getValue();
			result.append(" (").append(ref.path().name()).append(", ").append(info.getBeatCount()).append(") ");
		}
		return result.toString() + "}";
	}

	@Nullable
	public ActorRef pickNode() {

		if (correctNodes.isEmpty()) return null;

		List<ActorRef> candidates = new ArrayList<>(keySet());
		candidates.retainAll(correctNodes);

		// compute in advance a score for each node (will alter probability of it being chosen).
		// function: "sorted.get(candidates.get(i)).getQuiescence() + 1"
		// the +1 guarantees every node has a chance to be chosen
		// other examples: "sorted.size() - i + 1" (only based on ranking), "1" (uniform random)
		// TODO: more complex function (possibly by experiment setting)
		List<Long> nodeScores = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			nodeScores.add(i, get(candidates.get(i)).getQuiescence() + 1);
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
