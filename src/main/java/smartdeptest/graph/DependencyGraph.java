package smartdeptest.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DependencyGraph {
    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final Map<EdgeKey, GraphEdge> edges = new LinkedHashMap<>();

    public GraphNode addNode(GraphNode node) {
        return nodes.computeIfAbsent(node.id(), ignored -> node);
    }

    public GraphEdge addEdge(String source, String target, String type, Map<String, Object> metadata) {
        EdgeKey key = new EdgeKey(source, target, type);
        GraphEdge existing = edges.get(key);
        if (existing == null) {
            GraphEdge edge = new GraphEdge(source, target, type, metadata);
            edges.put(key, edge);
            return edge;
        }

        Map<String, Object> merged = new LinkedHashMap<>(existing.metadata());
        Object evidence = metadata.get("instructionTypes");
        if (evidence instanceof List<?> values) {
            Set<Object> combined = new LinkedHashSet<>();
            Object previous = merged.get("instructionTypes");
            if (previous instanceof List<?> previousValues) combined.addAll(previousValues);
            combined.addAll(values);
            merged.put("instructionTypes", List.copyOf(combined));
        }
        GraphEdge edge = new GraphEdge(source, target, type, merged);
        edges.put(key, edge);
        return edge;
    }

    public GraphNode getNode(String id) {
        return nodes.get(id);
    }

    public List<GraphNode> getNodes() {
        return List.copyOf(nodes.values());
    }

    public List<GraphEdge> getEdges() {
        return List.copyOf(edges.values());
    }

    public List<GraphEdge> getOutgoingEdges(String nodeId) {
        return edges.values().stream().filter(edge -> edge.source().equals(nodeId)).toList();
    }

    public List<GraphEdge> getIncomingEdges(String nodeId) {
        return edges.values().stream().filter(edge -> edge.target().equals(nodeId)).toList();
    }

    public List<String> findAffectedNodes(String apiNodeId) {
        List<String> affected = new ArrayList<>();
        for (GraphEdge edge : getIncomingEdges(apiNodeId)) {
            GraphNode node = getNode(edge.source());
            if (node != null && (node.type() == GraphNode.Type.CLASS || node.type() == GraphNode.Type.METHOD)) {
                affected.add(node.id());
            }
        }
        return List.copyOf(affected);
    }

    private record EdgeKey(String source, String target, String type) {}
}