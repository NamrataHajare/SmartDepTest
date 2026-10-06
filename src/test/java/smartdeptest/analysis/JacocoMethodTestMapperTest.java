package smartdeptest.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import smartdeptest.graph.DependencyGraph;
import smartdeptest.graph.DependencyGraphJsonExporter;
import smartdeptest.graph.DependencyGraphResult;
import smartdeptest.graph.GraphNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacocoMethodTestMapperTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsNABecauseAggregateCoverageCannotIdentifyIndividualTests() throws Exception {
        Path project = temporaryDirectory;
        Path target = project.resolve("target");
        Files.createDirectories(target.resolve("site/jacoco"));
        Files.createDirectories(target.resolve("surefire-reports"));
        Files.writeString(project.resolve("pom.xml"), "<project><modules/></project>", StandardCharsets.UTF_8);
        Files.writeString(target.resolve("site/jacoco/jacoco.xml"),
                "<report name=\"sample\"></report>", StandardCharsets.UTF_8);
        Files.writeString(target.resolve("surefire-reports/TEST-example.SampleTest.xml"),
                "<testsuite name=\"example.SampleTest\"><testcase classname=\"example.SampleTest\" "
                        + "name=\"coversApplicationMethod\"/></testsuite>", StandardCharsets.UTF_8);

        String methodId = "method:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("example.Application#run()V".getBytes(StandardCharsets.UTF_8));
        JacocoMethodTestMapper.CoverageResult coverage = new JacocoMethodTestMapper()
                .map(project, List.of(methodId), List.of(methodId), List.of());

        assertTrue(coverage.methodTestCoverage().isEmpty());
        assertTrue(coverage.selectedTests().isEmpty());
        assertTrue(coverage.failures().isEmpty());
        assertFalse(coverage.selectionNote().isBlank());
        assertEquals(1, coverage.testCoverageStatuses().size());
        assertEquals("example.SampleTest", coverage.testCoverageStatuses().get(0).testClass());
        assertEquals("coversApplicationMethod", coverage.testCoverageStatuses().get(0).testMethod());
        assertEquals("N/A", coverage.testCoverageStatuses().get(0).status());
        assertEquals(1, coverage.groupedSelectedTests().size());
        assertEquals("DIRECT", coverage.groupedSelectedTests().get(0).impactType());
        assertEquals("N/A", coverage.groupedSelectedTests().get(0).selectionStatus());
        assertTrue(coverage.groupedSelectedTests().get(0).selectedTests().isEmpty());

        DependencyGraphResult enriched = new DependencyGraphResult(
                new DependencyGraph(), List.of(), List.of(), List.of(methodId), List.of(), List.of(methodId),
                coverage.methodTestCoverage(), coverage.selectedTests(), coverage.groupedSelectedTests(),
                coverage.testCoverageStatuses());
        String json = new DependencyGraphJsonExporter().toJson(enriched);
        assertTrue(json.contains("\"selectionStatus\":\"N/A\""));
        assertTrue(json.contains("\"selectionNote\":\"JaCoCo XML"));
        assertTrue(json.contains("\"selectedTests\":[]"));
        assertTrue(json.contains("\"testCoverageStatuses\":[{\"testClass\":\"example.SampleTest\""));
        assertTrue(json.contains("\"status\":\"N/A\""));
    }

    @Test
    void reportsMissingCoverageArtifactsWithoutInventingTestMappings() throws Exception {
        Files.writeString(temporaryDirectory.resolve("pom.xml"),
                "<project><modules/></project>", StandardCharsets.UTF_8);
        String methodId = "method:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("example.Application#run()V".getBytes(StandardCharsets.UTF_8));

        JacocoMethodTestMapper.CoverageResult coverage = new JacocoMethodTestMapper()
                .map(temporaryDirectory, List.of(methodId));

        assertFalse(coverage.failures().isEmpty());
        assertTrue(coverage.failures().stream().anyMatch(message -> message.contains("No JaCoCo XML")));
        assertTrue(coverage.methodTestCoverage().isEmpty());
        assertTrue(coverage.selectedTests().isEmpty());
        assertEquals("N/A", coverage.groupedSelectedTests().get(0).selectionStatus());
    }

    @Test
    void mapsMavenInvokerReportingFixturesThroughTheCurrentImpactGraph() throws Exception {
        Path project = temporaryDirectory;
        Files.createDirectories(project.resolve("target/classes/META-INF/maven"));
        Files.createDirectories(project.resolve("target/classes/sample"));
        Files.createDirectories(project.resolve("src/it/report-fixture"));
        Files.writeString(project.resolve("pom.xml"),
                "<project><groupId>com.example</groupId><artifactId>example-maven-plugin</artifactId>"
                        + "<packaging>maven-plugin</packaging><modules/></project>", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("target/classes/META-INF/maven/plugin.xml"),
                "<plugin><mojos><mojo><goal>report</goal><implementation>sample.ReportMojo</implementation>"
                        + "<requiresReports>false</requiresReports></mojo></mojos></plugin>",
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve("src/it/report-fixture/pom.xml"),
                "<project><reporting><plugins><plugin><groupId>com.example</groupId>"
                        + "<artifactId>example-maven-plugin</artifactId></plugin></plugins></reporting></project>",
                StandardCharsets.UTF_8);
        writeClass(project.resolve("target/classes/sample/ReportMojo.class"),
                "sample/ReportMojo", "sample/ReportBase");
        writeClass(project.resolve("target/classes/sample/ReportBase.class"),
                "sample/ReportBase", "java/lang/Object");

        String reportMethod = methodId("sample.ReportBase", "executeReport", "()V");
        String affectedMethod = methodId("sample.Target", "changed", "()V");
        DependencyGraph graph = new DependencyGraph();
        graph.addNode(methodNode(reportMethod, "sample.ReportBase", "executeReport", "()V"));
        graph.addNode(methodNode(affectedMethod, "sample.Target", "changed", "()V"));
        graph.addEdge(reportMethod, affectedMethod, "CALLS", Map.of());
        DependencyGraphResult impact = new DependencyGraphResult(
                graph, List.of(), List.of(), List.of(affectedMethod), List.of(), List.of(affectedMethod));

        JacocoMethodTestMapper.CoverageResult coverage =
                new JacocoMethodTestMapper().map(project, impact);

        assertEquals(List.of("src/it/report-fixture#report"),
                coverage.groupedSelectedTests().get(0).selectedTests());
        assertEquals(List.of("src/it/report-fixture#report"), coverage.selectedTests());
        assertEquals("DIRECT", coverage.groupedSelectedTests().get(0).impactType());
        assertEquals("SELECTED", coverage.groupedSelectedTests().get(0).selectionStatus());
    }

    private static GraphNode methodNode(String id, String className, String methodName, String descriptor) {
        return new GraphNode(id, GraphNode.Type.METHOD,
                Map.of("className", className, "methodName", methodName, "descriptor", descriptor));
    }

    private static String methodId(String className, String methodName, String descriptor) {
        String signature = className + "#" + methodName + descriptor;
        return "method:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signature.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeClass(Path path, String className, String superClass) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, superClass, null);
        writer.visitEnd();
        Files.write(path, writer.toByteArray());
    }
}
