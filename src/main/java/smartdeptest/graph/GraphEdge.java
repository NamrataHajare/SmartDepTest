package smartdeptest.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record GraphEdge(String source, String target, String type, Map<String, Object> metadata) {
    public GraphEdge {
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}