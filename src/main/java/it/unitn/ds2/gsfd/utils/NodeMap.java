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

		// order nodes by their quiescence value
		Map<ActorRef, NodeInfo> sorted = entrySet().stream()
			.sorted((e1, e2) -> e2.getValue().getQuiescence() - e1.getValue().getQuiescence())
			.collect(Collectors.toMap(Entry::getKey, Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
		sorted.keySet().retainAll(correctNodes);

		List<ActorRef> candidates = new ArrayList<>(sorted.keySet());
		// generate a random number whose value ranges from 0.0 to the sum of the values of a function
		// function: "sorted.get(candidates.get(i)).getQuiescence()"
		// other examples: "sorted.size()-i" (only based on ranking), "1" (totally random)
		// TODO: more complex function (possibly by experiment setting)
		double randomMultiplier = 0;
		for (int i = 0; i < sorted.size(); i++) {
			randomMultiplier += 1;
			//sorted.get(candidates.get(i)).getQuiescence();
		}
		Random r = new Random();
		double randomDouble = r.nextDouble() * randomMultiplier;

		// subtract function in terms of i to the total until negative value is reached
		// the corresponding iteration is the random number chosen
		int k = 0;
		randomDouble = randomDouble - sorted.size() - k;
		while (randomDouble >= 0) {
			k++;
			randomDouble = randomDouble - sorted.size() - k;
		}

		return candidates.get(k);
	}
}
