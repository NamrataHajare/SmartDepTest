package smartdeptest.graph;

import smartdeptest.analysis.APIChangeResult;
import smartdeptest.analysis.APIUsageResult;
import smartdeptest.analysis.APIUsageResult.ApplicationCall;
import smartdeptest.analysis.ApiChange;
import smartdeptest.analysis.DependencyApiResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DependencyGraphBuilder {
    public DependencyGraphResult build(APIChangeResult apiChanges, APIUsageResult usage) {
        DependencyGraph graph = new DependencyGraph();
        Set<String> affectedNodes = new LinkedHashSet<>();
        Set<String> directlyImpactedMethods = new LinkedHashSet<>();
        List<ImpactPath> impactPaths = new ArrayList<>();

        for (DependencyApiResult dependency : apiChanges.dependencies()) {
            String version = dependency.newVersion().isBlank() ? dependency.oldVersion() : dependency.newVersion();
            String dependencyId = "dependency:" + dependency.groupId() + ":" + dependency.artifactId() + ":" + version;
            Map<String, Object> dependencyMetadata = new LinkedHashMap<>();
            dependencyMetadata.put("groupId", dependency.groupId());
            dependencyMetadata.put("artifactId", dependency.artifactId());
            dependencyMetadata.put("version", version);
            dependencyMetadata.put("oldVersion", dependency.oldVersion());
            dependencyMetadata.put("newVersion", dependency.newVersion());
            dependencyMetadata.put("scope", dependency.newScope().isBlank()
                    ? dependency.oldScope() : dependency.newScope());
            dependencyMetadata.put("oldScope", dependency.oldScope());
            dependencyMetadata.put("newScope", dependency.newScope());
            dependencyMetadata.put("direct", !dependency.dependencyManagement());
            dependencyMetadata.put("transitiveStatus", "NOT_ANALYZED");
            dependencyMetadata.put("declarationType", dependency.dependencyManagement()
                    ? "DEPENDENCY_MANAGEMENT" : "DIRECT_POM_DECLARATION");
            dependencyMetadata.put("dependencyManagement", dependency.dependencyManagement());
            dependencyMetadata.put("pomPath", dependency.pomPath());
            dependencyMetadata.put("analysisStatus", dependency.status().name());
            graph.addNode(new GraphNode(dependencyId, GraphNode.Type.DEPENDENCY, dependencyMetadata));

            APIUsageResult.DependencyImpact dependencyImpact = findImpact(usage, dependency);
            for (ApiChange change : dependency.changes()) {
                String apiId = apiNodeId(dependencyId, change);
                graph.addNode(new GraphNode(apiId, GraphNode.Type.API, apiMetadata(dependency, change)));
                graph.addEdge(dependencyId, apiId, "PROVIDES", Map.of());

                if (dependencyImpact == null
                        || dependencyImpact.classification() == APIUsageResult.Classification.ANALYSIS_UNAVAILABLE) {
                    continue;
                }
                dependencyImpact.findings().stream()
                        .filter(APIUsageResult.UsageFinding::used)
                        .filter(finding -> finding.change().equals(change))
                        .forEach(finding -> addUsage(graph, dependencyId, apiId, finding,
                            affectedNodes, directlyImpactedMethods, impactPaths));
            }
        }

                for (ApplicationCall call : usage.applicationCalls()) {
                    String callerId = addApplicationMethod(graph, call.sourceClassName(), call.sourceMethodName(),
                        call.sourceMethodDescriptor());
                    String calleeId = addApplicationMethod(graph, call.targetClassName(), call.targetMethodName(),
                        call.targetMethodDescriptor());
                        graph.addEdge(callerId, calleeId, "CALLS",
                            Map.of("instructionTypes", List.of(call.instructionType())));
                }

                DependencyGraphResult directResult = new DependencyGraphResult(graph, List.copyOf(affectedNodes),
                    impactPaths, List.copyOf(directlyImpactedMethods), List.of(), List.of());
                return new ImpactPropagator().propagate(directResult);
    }

    private static void addUsage(DependencyGraph graph, String dependencyId, String apiId,
                                 APIUsageResult.UsageFinding finding, Set<String> affectedNodes,
                             Set<String> directlyImpactedMethods, List<ImpactPath> impactPaths) {
        ApiChange change = finding.change();
        for (APIUsageResult.UsageLocation location : finding.locations()) {
                String className = location.className();
                String classId = addApplicationClass(graph, className);
            graph.addEdge(classId, apiId, "USES",
                    Map.of("instructionTypes", List.of(location.instructionType())));

            List<String> pathNodeIds = new ArrayList<>(List.of(dependencyId, apiId, classId));
            if (isApplicationMethod(location)) {
                String methodId = addApplicationMethod(graph, className, location.methodName(),
                    location.methodDescriptor());
                graph.addEdge(methodId, apiId, "USES",
                        Map.of("instructionTypes", List.of(location.instructionType())));
                pathNodeIds.add(methodId);
                affectedNodes.add(methodId);
                directlyImpactedMethods.add(methodId);
            }
            affectedNodes.add(classId);
            impactPaths.add(new ImpactPath("DIRECT", pathNodeIds, location.instructionType()));
        }
    }

    private static String addApplicationMethod(DependencyGraph graph, String internalClassName,
                                               String methodName, String descriptor) {
        String classId = addApplicationClass(graph, internalClassName);
        String className = binaryName(internalClassName);
        String methodId = "method:" + encode(className + "#" + methodName + descriptor);
        Map<String, Object> methodMetadata = new LinkedHashMap<>();
        methodMetadata.put("className", className);
        methodMetadata.put("methodName", methodName);
        methodMetadata.put("descriptor", descriptor);
        graph.addNode(new GraphNode(methodId, GraphNode.Type.METHOD, methodMetadata));
        graph.addEdge(classId, methodId, "CONTAINS", Map.of());
        return methodId;
    }

    private static String addApplicationClass(DependencyGraph graph, String internalClassName) {
        String className = binaryName(internalClassName);
        String classId = "class:" + encode(className);
        graph.addNode(new GraphNode(classId, GraphNode.Type.CLASS, Map.of("className", className)));
        return classId;
    }

    private static boolean isApplicationMethod(APIUsageResult.UsageLocation location) {
        return !location.methodName().isBlank()
                && !"<class>".equals(location.methodName())
                && !"<field>".equals(location.methodName())
                && !location.methodDescriptor().isBlank();
    }

    private static Map<String, Object> apiMetadata(DependencyApiResult dependency, ApiChange change) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("className", binaryName(change.className()));
        metadata.put("internalClassName", change.className());
        metadata.put("memberName", change.memberName());
        metadata.put("changeType", change.kind().name());
        metadata.put("oldSignature", change.oldSignature());
        metadata.put("newSignature", change.newSignature());
        metadata.put("signature", change.newSignature().isBlank() ? change.oldSignature() : change.newSignature());
        metadata.put("oldDescriptor", change.oldDescriptor());
        metadata.put("newDescriptor", change.newDescriptor());
        metadata.put("potentiallyIncompatible", change.potentiallyIncompatible());
        metadata.put("oldVersion", dependency.oldVersion());
        metadata.put("newVersion", dependency.newVersion());
        return metadata;
    }

    private static String apiNodeId(String dependencyId, ApiChange change) {
        String key = change.kind() + "\u0000" + change.className() + "\u0000" + change.memberName()
                + "\u0000" + change.oldDescriptor() + "\u0000" + change.newDescriptor();
        return "api:" + encode(dependencyId + "\u0000" + key);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static APIUsageResult.DependencyImpact findImpact(APIUsageResult usage,
                                                               DependencyApiResult dependency) {
        return usage.dependencies().stream()
                .filter(impact -> impact.dependencyKey().equals(dependency.dependencyKey()))
                .filter(impact -> impact.oldVersion().equals(dependency.oldVersion()))
                .filter(impact -> impact.newVersion().equals(dependency.newVersion()))
                .filter(impact -> impact.oldClassifier().equals(dependency.oldClassifier()))
                .filter(impact -> impact.newClassifier().equals(dependency.newClassifier()))
                .filter(impact -> impact.pomPath().equals(dependency.pomPath()))
                .filter(impact -> impact.dependencyManagement() == dependency.dependencyManagement())
                .findFirst().orElse(null);
    }
}