package smartdeptest.graph;

import java.util.List;

public record ImpactPath(String impactType, List<String> nodeIds, String instructionType) {
    public ImpactPath {
        nodeIds = List.copyOf(nodeIds);
    }
}