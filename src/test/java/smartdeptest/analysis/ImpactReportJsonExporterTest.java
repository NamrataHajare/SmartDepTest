package smartdeptest.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import smartdeptest.dependency.DependencyChangeResult;
import smartdeptest.graph.DependencyGraph;
import smartdeptest.graph.DependencyGraphResult;
import smartdeptest.graph.GraphNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ImpactReportJsonExporterTest {
    @TempDir
    Path project;

    @Test
    void exportsEmptyResultAndEscapesJsonStrings() {
        DependencyChangeResult changes = new DependencyChangeResult(
                project.toString(), "abc123", "prev456", "message \"quoted\"\nnext",
                List.of(), List.of());
        DependencyGraphResult graph = new DependencyGraphResult(
                new DependencyGraph(), List.of(), List.of());
        JacocoMethodTestMapper.CoverageResult coverage =
                new JacocoMethodTestMapper.CoverageResult(
                        List.of(), List.of(), List.of(), List.of(), List.of(), "");

        String json = new ImpactReportJsonExporter().toJson(
                new ImpactReportJsonExporter().toReport(
                        changes,
                        new APIChangeResult(project.toString(), List.of()),
                        new APIUsageResult(project.toString(), List.of()),
                        graph,
                        coverage));

        assertTrue(json.contains("\"schemaVersion\":1"));
        assertTrue(json.contains("\"tests\":[]"));
        assertTrue(json.contains("\"baseline\":null"));
        assertTrue(json.contains("\"commitMessage\":\"message \\\"quoted\\\"\\nnext\""));
    }

    @Test
    void exportsOnlyExistingSourceLocationsAndRealTestCallPaths() throws Exception {
        Path applicationSource = project.resolve("src/main/java/sample/Service.java");
        Path testSource = project.resolve("src/test/java/sample/ServiceTest.java");
        Files.createDirectories(applicationSource.getParent());
        Files.createDirectories(testSource.getParent());
        Files.writeString(applicationSource, "package sample;\npublic class Service {\n    public void execute() {}\n}\n");
        Files.writeString(testSource, "package sample;\nclass ServiceTest {\n    void testRun() {}\n}\n");

        String methodId = "method:service";
        DependencyGraph dependencyGraph = new DependencyGraph();
        dependencyGraph.addNode(new GraphNode(methodId, GraphNode.Type.METHOD, Map.of(
                "className", "sample.Service",
                "methodName", "execute",
                "descriptor", "()V",
                "modulePath", project.toString())));
        DependencyGraphResult graph = new DependencyGraphResult(
                dependencyGraph,
                List.of(methodId),
                List.of(),
                List.of(methodId),
                List.of(),
                List.of(methodId));
        String testId = "sample.ServiceTest#testRun";
        JacocoMethodTestMapper.CoverageResult coverage =
                new JacocoMethodTestMapper.CoverageResult(
                        List.of(new DependencyGraphResult.MethodTestCoverage(
                                methodId, "sample.ServiceTest", "testRun")),
                        List.of(testId),
                        List.of(new DependencyGraphResult.AffectedMethodTestGroup(
                                methodId, "DIRECT", List.of(testId))),
                        List.of(),
                        List.of(),
                        "Static test-bytecode path evidence.",
                        List.of(new JacocoMethodTestMapper.TestCallPath(
                                testId, methodId,
                                List.of("sample.ServiceTest#testRun", methodId))),
                        1);

        DependencyChangeResult changes = new DependencyChangeResult(
                project.toString(), "abc123", "prev456", "", List.of(), List.of());
        Map<String, Object> result = new ImpactReportJsonExporter().toReport(
                changes,
                new APIChangeResult(project.toString(), List.of()),
                new APIUsageResult(project.toString(), List.of()),
                graph,
                coverage);
        String json = new ImpactReportJsonExporter().toJson(result);

        assertTrue(json.contains(jsonString(applicationSource.toAbsolutePath().toString())));
        assertTrue(json.contains(jsonString(testSource.toAbsolutePath().toString())));
        assertTrue(json.contains("\"line\":3"));
        assertTrue(json.contains("sample.ServiceTest#testRun"));
        assertTrue(json.contains("sample.Service.execute()V"));
        assertTrue(json.contains("\"classification\":\"DIRECT\""));
    }

    private static String jsonString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
