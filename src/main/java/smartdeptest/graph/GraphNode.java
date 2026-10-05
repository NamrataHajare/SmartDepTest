package smartdeptest.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record GraphNode(String id, Type type, Map<String, Object> metadata) {
    public enum Type { DEPENDENCY, API, CLASS, METHOD }

    public GraphNode {
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}