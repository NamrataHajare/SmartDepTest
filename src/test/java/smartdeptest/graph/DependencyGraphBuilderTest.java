package smartdeptest.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import smartdeptest.analysis.APIChangeResult;
import smartdeptest.analysis.APIUsageResult;
import smartdeptest.analysis.ApiChange;
import smartdeptest.analysis.DependencyApiResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyGraphBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsDependencyApiClassAndMethodNodesFromDirectUsageEvidence() {
        ApiChange changedMethod = methodChange("process", "(Ljava/lang/String;)V");
        APIChangeResult changes = changes(dependency("org.example", "sample-library", List.of(changedMethod)));
        APIUsageResult usage = usage(impact("org.example", "sample-library", List.of(
                finding(changedMethod, true,
                        location("com/example/First", "run", "()V", "INVOKEVIRTUAL"),
                        location("com/example/First", "close", "()V", "INVOKEINTERFACE"))
        )));

        DependencyGraphResult result = new DependencyGraphBuilder().build(changes, usage);

        assertEquals(1, count(result, GraphNode.Type.DEPENDENCY));
        assertEquals(1, count(result, GraphNode.Type.API));
        assertEquals(1, count(result, GraphNode.Type.CLASS));
        assertEquals(2, count(result, GraphNode.Type.METHOD));
        assertEquals(1, edgesOfType(result, "PROVIDES"));
        assertEquals(3, edgesOfType(result, "USES"));
        assertEquals(2, edgesOfType(result, "CONTAINS"));
        assertEquals(0, edgesOfType(result, "CALLS"));
        assertEquals(2, result.impactPaths().size());
        assertEquals(3, result.affectedNodes().size());
        assertTrue(instructionTypes(result, "USES").containsAll(List.of(
                "INVOKEVIRTUAL", "INVOKEINTERFACE")));
        GraphNode dependencyNode = result.graph().getNodes().stream()
                .filter(node -> node.type() == GraphNode.Type.DEPENDENCY).findFirst().orElseThrow();
        assertEquals("org.example", dependencyNode.metadata().get("groupId"));
        assertEquals("sample-library", dependencyNode.metadata().get("artifactId"));
        assertEquals("2.0", dependencyNode.metadata().get("version"));
        assertEquals("NOT_ANALYZED", dependencyNode.metadata().get("transitiveStatus"));
    }

    @Test
    void unusedApiDoesNotCreateApplicationEdgesOrAffectedNodes() {
        ApiChange changedMethod = methodChange("removed", "()V");
        APIChangeResult changes = changes(dependency("org.example", "sample-library", List.of(changedMethod)));
        APIUsageResult usage = usage(impact("org.example", "sample-library", List.of(
                finding(changedMethod, false,
                        location("com/example/Unused", "run", "()V", "INVOKEVIRTUAL"))
        )));

        DependencyGraphResult result = new DependencyGraphBuilder().build(changes, usage);

        assertEquals(1, count(result, GraphNode.Type.DEPENDENCY));
        assertEquals(1, count(result, GraphNode.Type.API));
        assertEquals(0, count(result, GraphNode.Type.CLASS));
        assertEquals(1, edgesOfType(result, "PROVIDES"));
        assertEquals(0, edgesOfType(result, "USES"));
        assertTrue(result.affectedNodes().isEmpty());
        assertTrue(result.impactPaths().isEmpty());
    }

    @Test
    void supportsMultipleChangedApisAndDependencies() {
        ApiChange firstChange = methodChange("first", "()V");
        ApiChange unusedChange = methodChange("unused", "()V");
        ApiChange secondChange = methodChange("second", "(I)V");
        DependencyApiResult firstDependency = dependency("org.example", "first-library",
                List.of(firstChange, unusedChange));
        DependencyApiResult secondDependency = dependency("org.other", "second-library", List.of(secondChange));
        APIUsageResult usage = usage(
                impact("org.example", "first-library", List.of(finding(firstChange, true,
                        location("app/First", "callFirst", "()V", "INVOKEVIRTUAL")))),
                impact("org.other", "second-library", List.of(finding(secondChange, true,
                        location("app/Second", "callSecond", "()V", "INVOKESTATIC")))));

        DependencyGraphResult result = new DependencyGraphBuilder()
                .build(changes(firstDependency, secondDependency), usage);

        assertEquals(2, count(result, GraphNode.Type.DEPENDENCY));
        assertEquals(3, count(result, GraphNode.Type.API));
        assertEquals(2, count(result, GraphNode.Type.CLASS));
        assertEquals(2, count(result, GraphNode.Type.METHOD));
        assertEquals(2, result.impactPaths().size());
        assertEquals(3, edgesOfType(result, "PROVIDES"));
    }

    @Test
    void exportsRequiredJsonCollectionsAndWritesTheAnalysis() throws Exception {
        ApiChange changedField = new ApiChange(ApiChange.Kind.FIELD_MODIFIED, "org/example/Options",
                "mode", "Ljava/lang/String;", "Ljava/lang/Integer;", "Ljava/lang/String;",
                "Ljava/lang/Integer;", true);
        DependencyGraphResult result = new DependencyGraphBuilder().build(
                changes(dependency("org.example", "sample-library", List.of(changedField))),
                usage(impact("org.example", "sample-library", List.of(finding(changedField, true,
                        location("app/Runner", "configure", "()V", "GETFIELD"))))));
        result = new DependencyGraphResult(
                result.graph(), result.affectedNodes(), result.impactPaths(),
                result.directlyImpactedMethods(), result.indirectlyAffectedMethods(),
                result.allAffectedMethods(), result.methodTestMapping(), result.selectedTests(),
                result.groupedSelectedTests(), result.testCoverageStatuses(),
                new smartdeptest.analysis.APIUsageResult.AnalysisSummary(1, 1, 2, 1, 1, 0, 0),
                List.of("A class directory could not be enumerated."));
        DependencyGraphJsonExporter exporter = new DependencyGraphJsonExporter();
        String json = exporter.toJson(result);
        Path output = temporaryDirectory.resolve("graph.json");
        exporter.write(result, output);

        assertTrue(json.startsWith("{\"nodes\":["));
        assertTrue(json.contains("\"edges\":["));
        assertTrue(json.contains("\"affectedNodes\":["));
        assertTrue(json.contains("\"impactPaths\":["));
        assertTrue(json.contains("\"analysisDiagnostics\":[\"A class directory could not be enumerated.\"],"
                + "\"affectedNodes\":["));
        assertTrue(!json.contains("\"analysisDiagnostics\":[]],\"affectedNodes\":["));
        assertTrue(json.contains("\"type\":\"DEPENDENCY\""));
        assertTrue(json.contains("\"type\":\"API\""));
        assertTrue(json.contains("\"type\":\"CLASS\""));
        assertTrue(json.contains("\"type\":\"METHOD\""));
        assertEquals(json, Files.readString(output));
        assertNotNull(result.graph().getNode(result.impactPaths().get(0).nodeIds().get(0)));
                assertTrue(json.contains("\"directlyImpactedMethods\":["));
                assertTrue(json.contains("\"indirectlyAffectedMethods\":["));
                assertTrue(json.contains("\"allAffectedMethods\":["));
    }

        @Test
        void propagatesBackwardsThroughRecursiveCallCyclesWithoutDuplicates() {
                DependencyGraph graph = new DependencyGraph();
                String callerA = addMethod(graph, "app.A", "first", "()V");
                String callerB = addMethod(graph, "app.B", "second", "()V");
                String direct = addMethod(graph, "app.C", "third", "()V");
                graph.addEdge(callerA, callerB, "CALLS", Map.of());
                graph.addEdge(callerB, direct, "CALLS", Map.of());
                graph.addEdge(direct, callerA, "CALLS", Map.of());
                graph.addEdge(callerA, callerB, "CALLS", Map.of());
                DependencyGraphResult initial = new DependencyGraphResult(graph, List.of(direct), List.of(),
                                List.of(direct, direct), List.of(), List.of());

                DependencyGraphResult result = new ImpactPropagator().propagate(initial);

                assertEquals(List.of(direct), result.directlyImpactedMethods());
                assertEquals(2, result.indirectlyAffectedMethods().size());
                assertTrue(result.indirectlyAffectedMethods().containsAll(List.of(callerA, callerB)));
                assertEquals(3, result.allAffectedMethods().size());
                assertEquals(3, result.allAffectedMethods().stream().distinct().count());
        }

    @Test
    void emptyAndNoImpactAnalysisExportsEmptyImpactCollections() {
        DependencyGraphResult result = new DependencyGraphBuilder().build(
                new APIChangeResult("", List.of()), new APIUsageResult("", List.of()));

        assertTrue(result.graph().getNodes().isEmpty());
        assertTrue(result.graph().getEdges().isEmpty());
        assertTrue(result.affectedNodes().isEmpty());
        assertTrue(result.impactPaths().isEmpty());
        assertEquals("{\"nodes\":[],\"edges\":[],\"applicationAnalysisSummary\":{"
                        + "\"modulesDiscovered\":0,\"modulesIncomplete\":0,"
                        + "\"classFilesDiscovered\":0,\"classFilesAnalyzed\":0,"
                        + "\"classFileFailures\":0,\"classDirectoryFailures\":0,"
                        + "\"duplicateClassFilesSkipped\":0},\"analysisDiagnostics\":[],"
                        + "\"affectedNodes\":[],"
                        + "\"directlyImpactedMethods\":[],\"indirectlyAffectedMethods\":[],"
                        + "\"allAffectedMethods\":[],\"impactPaths\":[]}",
                new DependencyGraphJsonExporter().toJson(result));
    }

    private static String addMethod(DependencyGraph graph, String className, String methodName,
                                    String descriptor) {
        String classId = "class:" + className;
        String methodId = "method:" + className + "#" + methodName + descriptor;
        graph.addNode(new GraphNode(classId, GraphNode.Type.CLASS, Map.of("className", className)));
        graph.addNode(new GraphNode(methodId, GraphNode.Type.METHOD,
                Map.of("className", className, "methodName", methodName, "descriptor", descriptor)));
        graph.addEdge(classId, methodId, "CONTAINS", Map.of());
        return methodId;
    }

    private static int count(DependencyGraphResult result, GraphNode.Type type) {
        return (int) result.graph().getNodes().stream().filter(node -> node.type() == type).count();
    }

    private static long edgesOfType(DependencyGraphResult result, String type) {
        return result.graph().getEdges().stream().filter(edge -> edge.type().equals(type)).count();
    }

    private static List<String> instructionTypes(DependencyGraphResult result, String edgeType) {
        return result.graph().getEdges().stream().filter(edge -> edge.type().equals(edgeType))
                .flatMap(edge -> ((List<?>) edge.metadata().get("instructionTypes")).stream())
                .map(String.class::cast).toList();
    }

    private static ApiChange methodChange(String name, String descriptor) {
        return new ApiChange(ApiChange.Kind.METHOD_REMOVED, "org/example/Service", name,
                "", "", descriptor, "", true);
    }

    private static DependencyApiResult dependency(String groupId, String artifactId, List<ApiChange> changes) {
        return new DependencyApiResult(groupId, artifactId, "1.0", "2.0", "", "", "compile", "compile",
                "jar", "jar", false, "pom.xml", "", "", "", "",
                DependencyApiResult.Status.ANALYZED, "", changes);
    }

    private static APIChangeResult changes(DependencyApiResult... dependencies) {
        return new APIChangeResult("", List.of(dependencies));
    }

    private static APIUsageResult usage(APIUsageResult.DependencyImpact... impacts) {
        return new APIUsageResult("", List.of(impacts));
    }

    private static APIUsageResult.DependencyImpact impact(String groupId, String artifactId,
                                                           List<APIUsageResult.UsageFinding> findings) {
        return new APIUsageResult.DependencyImpact(groupId + ":" + artifactId, "1.0", "2.0", "", "",
                "pom.xml", "compile", "compile", "jar", "jar", false,
                APIUsageResult.Classification.POTENTIAL_IMPACT, "", findings);
    }

    private static APIUsageResult.UsageFinding finding(ApiChange change, boolean used,
                                                        APIUsageResult.UsageLocation... locations) {
        return new APIUsageResult.UsageFinding(change, used, List.of(locations));
    }

    private static APIUsageResult.UsageLocation location(String className, String methodName,
                                                          String descriptor, String instructionType) {
        return new APIUsageResult.UsageLocation(className, methodName, descriptor, instructionType);
    }
}