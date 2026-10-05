package smartdeptest.graph;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ImpactPropagator {
    public DependencyGraphResult propagate(DependencyGraphResult result) {
        Set<String> directlyImpacted = new LinkedHashSet<>(result.directlyImpactedMethods());
        Set<String> indirectlyAffected = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(directlyImpacted);

        while (!pending.isEmpty()) {
            String callee = pending.removeFirst();
            for (GraphEdge edge : result.graph().getIncomingEdges(callee)) {
                if (!"CALLS".equals(edge.type())) continue;
                GraphNode caller = result.graph().getNode(edge.source());
                if (caller == null || caller.type() != GraphNode.Type.METHOD
                        || directlyImpacted.contains(caller.id())) continue;
                if (indirectlyAffected.add(caller.id())) pending.addLast(caller.id());
            }
        }

        Set<String> allAffected = new LinkedHashSet<>(directlyImpacted);
        allAffected.addAll(indirectlyAffected);
        return new DependencyGraphResult(result.graph(), result.affectedNodes(), result.impactPaths(),
                List.copyOf(directlyImpacted), List.copyOf(indirectlyAffected), List.copyOf(allAffected));
    }
}