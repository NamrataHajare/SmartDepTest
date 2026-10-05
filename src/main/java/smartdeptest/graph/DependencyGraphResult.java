package smartdeptest.graph;

import java.util.List;

public record DependencyGraphResult(DependencyGraph graph, List<String> affectedNodes,
                                    List<ImpactPath> impactPaths,
                                    List<String> directlyImpactedMethods,
                                    List<String> indirectlyAffectedMethods,
                                    List<String> allAffectedMethods) {
    public DependencyGraphResult(DependencyGraph graph, List<String> affectedNodes,
                                 List<ImpactPath> impactPaths) {
        this(graph, affectedNodes, impactPaths, List.of(), List.of(), List.of());
    }

    public DependencyGraphResult {
        affectedNodes = List.copyOf(affectedNodes);
        impactPaths = List.copyOf(impactPaths);
        directlyImpactedMethods = List.copyOf(directlyImpactedMethods);
        indirectlyAffectedMethods = List.copyOf(indirectlyAffectedMethods);
        allAffectedMethods = List.copyOf(allAffectedMethods);
    }
}