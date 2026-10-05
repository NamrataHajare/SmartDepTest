package smartdeptest.graph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

public final class DependencyGraphJsonExporter {
    public String toJson(DependencyGraphResult result) {
        StringBuilder json = new StringBuilder();
        json.append("{\"nodes\":[");
        appendSeparated(json, result.graph().getNodes().iterator(), node -> {
            json.append("{\"id\":");
            appendValue(json, node.id());
            json.append(",\"type\":");
            appendValue(json, node.type().name());
            json.append(",\"metadata\":");
            appendValue(json, node.metadata());
            json.append('}');
        });
        json.append("],\"edges\":[");
        appendSeparated(json, result.graph().getEdges().iterator(), edge -> {
            json.append("{\"source\":");
            appendValue(json, edge.source());
            json.append(",\"target\":");
            appendValue(json, edge.target());
            json.append(",\"type\":");
            appendValue(json, edge.type());
            json.append(",\"metadata\":");
            appendValue(json, edge.metadata());
            json.append('}');
        });
        json.append("],\"affectedNodes\":");
        appendValue(json, result.affectedNodes());
        json.append(",\"directlyImpactedMethods\":");
        appendValue(json, result.directlyImpactedMethods());
        json.append(",\"indirectlyAffectedMethods\":");
        appendValue(json, result.indirectlyAffectedMethods());
        json.append(",\"allAffectedMethods\":");
        appendValue(json, result.allAffectedMethods());
        json.append(",\"impactPaths\":[");
        appendSeparated(json, result.impactPaths().iterator(), path -> {
            json.append("{\"impactType\":");
            appendValue(json, path.impactType());
            json.append(",\"nodeIds\":");
            appendValue(json, path.nodeIds());
            json.append(",\"instructionType\":");
            appendValue(json, path.instructionType());
            json.append('}');
        });
        return json.append("]}").toString();
    }

    public void write(DependencyGraphResult result, Path output) throws IOException {
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(output, toJson(result), StandardCharsets.UTF_8);
    }

    private static <T> void appendSeparated(StringBuilder json, Iterator<T> items,
                                            java.util.function.Consumer<T> appendItem) {
        boolean first = true;
        while (items.hasNext()) {
            if (!first) json.append(',');
            appendItem.accept(items.next());
            first = false;
        }
    }

    private static void appendValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof String text) {
            appendString(json, text);
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof Map<?, ?> map) {
            json.append('{');
            Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
            boolean first = true;
            while (entries.hasNext()) {
                Map.Entry<?, ?> entry = entries.next();
                if (!first) json.append(',');
                appendString(json, String.valueOf(entry.getKey()));
                json.append(':');
                appendValue(json, entry.getValue());
                first = false;
            }
            json.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            json.append('[');
            Iterator<?> items = iterable.iterator();
            boolean first = true;
            while (items.hasNext()) {
                if (!first) json.append(',');
                appendValue(json, items.next());
                first = false;
            }
            json.append(']');
        } else {
            appendString(json, String.valueOf(value));
        }
    }

    private static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) json.append(String.format("\\u%04x", (int) character));
                    else json.append(character);
                }
            }
        }
        json.append('"');
    }
}