package smartdeptest.analysis;

import smartdeptest.dependency.DependencyChangeResult;
import smartdeptest.graph.DependencyGraph;
import smartdeptest.graph.DependencyGraphResult;
import smartdeptest.graph.GraphEdge;
import smartdeptest.graph.GraphNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ImpactReportJsonExporter {
    public Map<String, Object> toReport(
            DependencyChangeResult dependencyChanges,
            APIChangeResult apiChanges,
            APIUsageResult usage,
            DependencyGraphResult graphResult,
            JacocoMethodTestMapper.CoverageResult coverage) {
        Path projectRoot = Path.of(dependencyChanges.getProjectPath())
                .toAbsolutePath().normalize();
        DependencyGraph graph = graphResult.graph();
        Map<String, String> displayNamesByMethodId = new LinkedHashMap<>();
        for (GraphNode node : graph.getNodes()) {
            if (node.type() == GraphNode.Type.METHOD) {
                displayNamesByMethodId.put(node.id(),
                        node.metadata().getOrDefault("className", "") + "."
                                + node.metadata().getOrDefault("methodName", "")
                                + node.metadata().getOrDefault("descriptor", ""));
            }
        }

        List<Map<String, Object>> dependencies = new ArrayList<>();
        for (DependencyApiResult dependency : apiChanges.dependencies()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("groupId", dependency.groupId());
            entry.put("artifactId", dependency.artifactId());
            entry.put("oldVersion", dependency.oldVersion());
            entry.put("newVersion", dependency.newVersion());
            entry.put("status", dependency.status().name());
            entry.put("message", dependency.message());
            entry.put("pomPath", dependency.pomPath());
            entry.put("commitId", dependencyChanges.getCommitId());
            entry.put("previousCommitId", dependencyChanges.getPreviousCommitId());
            entry.put("changeType", findChangeType(
                    dependencyChanges, dependency.groupId(), dependency.artifactId()));
            dependencies.add(entry);
        }

        Set<String> directMethods = new LinkedHashSet<>(
                graphResult.directlyImpactedMethods());
        Set<String> indirectMethods = new LinkedHashSet<>(
                graphResult.indirectlyAffectedMethods());
        Map<String, Map<String, Object>> methodsById = new LinkedHashMap<>();
        List<Map<String, Object>> affectedMethods = new ArrayList<>();
        for (String methodId : graphResult.allAffectedMethods()) {
            GraphNode node = graph.getNode(methodId);
            if (node == null) {
                continue;
            }
            Map<String, Object> method = methodDetails(
                    projectRoot, node, methodId, directMethods, indirectMethods);
            methodsById.put(methodId, method);
            affectedMethods.add(method);
        }

        Map<String, List<Map<String, Object>>> methodsByApi = new LinkedHashMap<>();
        for (GraphEdge edge : graph.getEdges()) {
            if (!"USES".equals(edge.type())) {
                continue;
            }
            GraphNode source = graph.getNode(edge.source());
            GraphNode target = graph.getNode(edge.target());
            if (source != null && source.type() == GraphNode.Type.METHOD
                    && target != null && target.type() == GraphNode.Type.API) {
                Map<String, Object> method = methodsById.get(source.id());
                if (method != null) {
                    methodsByApi.computeIfAbsent(target.id(), ignored -> new ArrayList<>())
                            .add(method);
                }
            }
        }

        List<Map<String, Object>> changedApis = new ArrayList<>();
        for (GraphNode node : graph.getNodes()) {
            if (node.type() != GraphNode.Type.API) {
                continue;
            }
            Map<String, Object> api = new LinkedHashMap<>(node.metadata());
            api.put("id", node.id());
            api.put("affectedMethods", methodsByApi.getOrDefault(node.id(), List.of()));
            changedApis.add(api);
        }

        Map<String, Set<String>> affectedByTest = new LinkedHashMap<>();
        for (DependencyGraphResult.MethodTestCoverage mapping
                : coverage.methodTestCoverage()) {
            String testId = mapping.testClass() + "#" + mapping.testMethod();
            affectedByTest.computeIfAbsent(testId, ignored -> new LinkedHashSet<>())
                    .add(mapping.applicationMethod());
        }

        Map<String, Map<String, Object>> pathsByTestAndMethod = new LinkedHashMap<>();
        for (JacocoMethodTestMapper.TestCallPath callPath : coverage.callPaths()) {
            Map<String, Object> byMethod = pathsByTestAndMethod.computeIfAbsent(
                    callPath.testId(), ignored -> new LinkedHashMap<>());
            byMethod.put(callPath.affectedMethod(), callPath.methods());
        }

        List<Map<String, Object>> selectedTests = new ArrayList<>();
        int directlyAffectedTests = 0;
        int indirectlyAffectedTests = 0;
        for (String testId : coverage.selectedTests()) {
            int separator = testId.lastIndexOf('#');
            if (separator < 1 || separator == testId.length() - 1) {
                continue;
            }
            String testClass = testId.substring(0, separator);
            String testMethod = testId.substring(separator + 1);
            Set<String> methodIds = affectedByTest.getOrDefault(testId, Set.of());
            boolean isDirect = methodIds.stream().anyMatch(directMethods::contains);
            if (isDirect) {
                directlyAffectedTests++;
            } else {
                indirectlyAffectedTests++;
            }

            Map<String, Object> test = new LinkedHashMap<>();
            test.put("id", testId);
            test.put("testClass", testClass);
            test.put("testMethod", testMethod);
            test.put("classification", isDirect ? "DIRECT" : "INDIRECT");
            test.put("source", sourceLocation(
                    projectRoot, testClass, testMethod, true));
            List<Map<String, Object>> testAffectedMethods = new ArrayList<>();
            List<List<String>> callPaths = new ArrayList<>();
            Map<String, Object> testPaths = pathsByTestAndMethod.getOrDefault(
                    testId, Map.of());
            for (String methodId : methodIds) {
                Map<String, Object> method = methodsById.get(methodId);
                if (method != null) {
                    testAffectedMethods.add(method);
                }
                Object path = testPaths.get(methodId);
                if (path instanceof List<?> pathItems) {
                    List<String> names = new ArrayList<>();
                    for (Object pathItem : pathItems) {
                        String pathId = String.valueOf(pathItem);
                        names.add(displayNamesByMethodId.getOrDefault(pathId, pathId));
                    }
                    callPaths.add(names);
                }
            }
            test.put("affectedMethods", testAffectedMethods);
            test.put("callPaths", callPaths);
            selectedTests.add(test);
        }

        List<String> warnings = new ArrayList<>(dependencyChanges.getDiagnostics());
        warnings.addAll(usage.diagnostics());
        warnings.addAll(graphResult.analysisDiagnostics());
        warnings.addAll(coverage.failures());
        for (DependencyApiResult dependency : apiChanges.dependencies()) {
            if (!dependency.message().isBlank()) {
                warnings.add(dependency.groupId() + ":" + dependency.artifactId()
                        + ": " + dependency.message());
            }
        }
        for (APIUsageResult.DependencyImpact impact : usage.dependencies()) {
            if (!impact.message().isBlank()) {
                warnings.add(impact.dependencyKey() + ": " + impact.message());
            }
        }
        if (!coverage.selectionNote().isBlank()) {
            warnings.add(coverage.selectionNote());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        Integer availableTestCount = graphResult.allAffectedMethods().isEmpty()
                ? null : coverage.availableTestCount();
        summary.put("availableTests", availableTestCount);
        summary.put("selectedTests", selectedTests.size());
        summary.put("directlyAffectedTests", directlyAffectedTests);
        summary.put("indirectlyAffectedTests", indirectlyAffectedTests);
        summary.put("notSelectedTests", availableTestCount == null ? null
                : Math.max(0, availableTestCount - selectedTests.size()));
        summary.put("unresolvedMappings", coverage.groupedSelectedTests().stream()
                .filter(group -> !"SELECTED".equals(group.selectionStatus()))
                .map(group -> Map.of(
                        "applicationMethod", group.affectedMethod(),
                        "status", group.selectionStatus(),
                        "reason", group.selectionNote()))
                .toList());
        summary.put("selectionEvidence", coverage.selectionNote());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("projectPath", projectRoot.toString());
        String dependencyStatus = apiChanges.dependencies().stream()
                .anyMatch(item -> item.status() == DependencyApiResult.Status.UNAVAILABLE)
                || usage.dependencies().stream()
                        .anyMatch(item -> item.classification()
                                == APIUsageResult.Classification.ANALYSIS_UNAVAILABLE)
                ? "PARTIAL" : apiChanges.dependencies().isEmpty()
                ? "NO_DEPENDENCY_CHANGE" : "ANALYZED";
        report.put("dependencyUpdate", Map.of(
                "commitId", nullToEmpty(dependencyChanges.getCommitId()),
                "previousCommitId", nullToEmpty(dependencyChanges.getPreviousCommitId()),
                "commitMessage", nullToEmpty(dependencyChanges.getCommitMessage()),
                "status", dependencyStatus));
        report.put("dependencies", dependencies);
        report.put("changedApis", changedApis);
        report.put("affectedMethods", affectedMethods);
        report.put("tests", selectedTests);
        report.put("testSummary", summary);
        report.put("warnings", warnings.stream().distinct().toList());
        report.put("errors", List.of());
        report.put("baseline", null);
        return report;
    }

    public void write(Map<String, Object> report, Path output) throws IOException {
        Path normalized = output.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(normalized, toJson(report), StandardCharsets.UTF_8);
    }

    public String toJson(Map<String, Object> report) {
        StringBuilder json = new StringBuilder();
        appendValue(json, report);
        return json.toString();
    }

    private static Map<String, Object> methodDetails(
            Path root,
            GraphNode node,
            String methodId,
            Set<String> directMethods,
            Set<String> indirectMethods) {
        String className = String.valueOf(node.metadata().getOrDefault("className", ""));
        String methodName = String.valueOf(node.metadata().getOrDefault("methodName", ""));
        String descriptor = String.valueOf(node.metadata().getOrDefault("descriptor", ""));
        String modulePath = String.valueOf(node.metadata().getOrDefault("modulePath", ""));
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("id", methodId);
        method.put("className", className);
        method.put("methodName", methodName);
        method.put("descriptor", descriptor);
        method.put("displayName", className + "." + methodName + descriptor);
        method.put("classification", directMethods.contains(methodId)
                ? "DIRECT" : indirectMethods.contains(methodId) ? "INDIRECT" : "UNKNOWN");
        method.put("reason", directMethods.contains(methodId)
                ? "Bytecode in this method references a changed dependency API."
                : "This method calls an application method that directly references a changed dependency API.");
        method.put("source", sourceLocation(
                root, modulePath, className, methodName, false));
        return method;
    }

    private static String findChangeType(
            DependencyChangeResult changes, String groupId, String artifactId) {
        return changes.getChanges().stream()
                .filter(change -> groupId.equals(change.getGroupId())
                        && artifactId.equals(change.getArtifactId()))
                .map(change -> change.getChangeType().name())
                .findFirst().orElse("UNKNOWN");
    }

    private static Map<String, Object> sourceLocation(
            Path root, String className, String memberName, boolean testSource) {
        String module = "";
        String qualifiedClass = className;
        int moduleSeparator = className.indexOf("::");
        if (moduleSeparator >= 0) {
            module = className.substring(0, moduleSeparator);
            qualifiedClass = className.substring(moduleSeparator + 2);
        }
        return sourceLocation(root, module, qualifiedClass, memberName, testSource);
    }

    private static Map<String, Object> sourceLocation(
            Path root, String modulePath, String className,
            String memberName, boolean testSource) {
        String outerClass = className.replaceAll("\\$.*$", "");
        String relativeClass = outerClass.replace('.', java.io.File.separatorChar)
                + ".java";
        Path module = modulePath == null || modulePath.isBlank()
                ? root : root.resolve(modulePath).normalize();
        if (!module.startsWith(root)) {
            module = root;
        }
        String sourceDirectory = testSource ? "src/test/java" : "src/main/java";
        Path candidate = module.resolve(sourceDirectory).resolve(relativeClass).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("path", "");
            missing.put("line", null);
            return missing;
        }

        Integer line = findDeclarationLine(candidate, memberName);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("path", candidate.toAbsolutePath().normalize().toString());
        source.put("line", line);
        return source;
    }

    private static Integer findDeclarationLine(Path source, String memberName) {
        if (memberName == null || memberName.isBlank() || memberName.startsWith("<")) {
            return null;
        }
        Pattern declaration = Pattern.compile(
                "^\\s*(?:(?:public|protected|private|static|final|synchronized|native|abstract|default|strictfp)\\s+)*"
                        + "[\\w.$<>?,\\[\\] ]+\\s+" + Pattern.quote(memberName)
                        + "\\s*\\(.*");
        try {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                if (declaration.matcher(lines.get(index)).matches()) {
                    return index + 1;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void appendValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof String text) {
            json.append('"');
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                switch (character) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\b' -> json.append("\\b");
                    case '\f' -> json.append("\\f");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            json.append(String.format("\\u%04x", (int) character));
                        } else {
                            json.append(character);
                        }
                    }
                }
            }
            json.append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof Map<?, ?> map) {
            json.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                appendValue(json, String.valueOf(entry.getKey()));
                json.append(':');
                appendValue(json, entry.getValue());
                first = false;
            }
            json.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            json.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    json.append(',');
                }
                appendValue(json, item);
                first = false;
            }
            json.append(']');
        } else {
            appendValue(json, String.valueOf(value));
        }
    }
}
