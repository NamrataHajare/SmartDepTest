package smartdeptest.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import smartdeptest.graph.DependencyGraph;
import smartdeptest.graph.DependencyGraphJsonExporter;
import smartdeptest.graph.DependencyGraphResult;
import smartdeptest.graph.GraphNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceLocationResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsApplicationAndTestSourceLocationsWithTheirMapping() throws Exception {
        Path module = temporaryDirectory.resolve("app");
        Path applicationSource = module.resolve("src/main/java/demo/Service.java");
        Path testSource = module.resolve("src/test/java/demo/FeatureTest.java");
        Files.createDirectories(applicationSource.getParent());
        Files.createDirectories(testSource.getParent());
        Files.createDirectories(module.resolve("target/classes/demo"));
        Files.createDirectories(module.resolve("target/test-classes/demo"));
        Files.writeString(applicationSource, "package demo; class Service {}");
        Files.writeString(testSource, "package demo; class FeatureTest {}");
        Files.write(module.resolve("target/classes/demo/Service.class"),
                classWithMethod("demo/Service", "Service.java", "execute", "()V", 19));
        Files.write(module.resolve("target/test-classes/demo/FeatureTest.class"),
                classWithMethod("demo/FeatureTest", "FeatureTest.java", "coversService", "()V", 31));
        Files.writeString(temporaryDirectory.resolve("pom.xml"),
                "<project><modules><module>app</module></modules></project>");
        Files.writeString(module.resolve("pom.xml"), "<project/>");

        String methodId = "method:demo.Service#execute()V";
        String testId = "demo.FeatureTest#coversService";
        DependencyGraph graph = new DependencyGraph();
        graph.addNode(new GraphNode(methodId, GraphNode.Type.METHOD, Map.of(
                "className", "demo.Service",
                "methodName", "execute",
                "descriptor", "()V",
                "modulePath", module.toString())));
        String classId = "class:demo.Service";
        graph.addNode(new GraphNode(classId, GraphNode.Type.CLASS, Map.of(
                "className", "demo.Service",
                "modulePath", module.toString())));
        DependencyGraphResult result = new DependencyGraphResult(
                graph,
                List.of(methodId, classId),
                List.of(),
                List.of(methodId),
                List.of(),
                List.of(methodId),
                List.of(new DependencyGraphResult.MethodTestCoverage(
                        methodId, "demo.FeatureTest", "coversService")),
                List.of(testId),
                List.of(new DependencyGraphResult.AffectedMethodTestGroup(
                        methodId, "DIRECT", List.of(testId))),
                List.of(),
                APIUsageResult.AnalysisSummary.empty(),
                List.of(),
                "Static call-path candidates.");

        String json = new DependencyGraphJsonExporter()
                .toJson(result, temporaryDirectory);

        assertTrue(json.contains("\"applicationMethodLocations\":[{\"methodId\":\""
                + methodId + "\",\"className\":\"demo.Service\",\"methodName\":\"execute\","
                + "\"descriptor\":\"()V\",\"sourcePath\":\"app/src/main/java/demo/Service.java\","
                + "\"lineNumber\":19,\"locationStatus\":\"AVAILABLE\"}]"));
        assertTrue(json.contains("\"applicationClassLocations\":[{\"classId\":\""
                + classId + "\",\"className\":\"demo.Service\","
                + "\"sourcePath\":\"app/src/main/java/demo/Service.java\","
                + "\"lineNumber\":null,\"locationStatus\":\"FILE_ONLY\"}]"));
        assertTrue(json.contains("\"testCaseLocations\":[{\"testId\":\""
                + testId + "\",\"testClass\":\"demo.FeatureTest\",\"testMethod\":\"coversService\","
                + "\"sourcePath\":\"app/src/test/java/demo/FeatureTest.java\","
                + "\"lineNumber\":31,\"locationStatus\":\"AVAILABLE\"}]"));
        assertTrue(json.contains("\"applicationMethod\":\"" + methodId
                + "\",\"testClass\":\"demo.FeatureTest\",\"testMethod\":\"coversService\""));
        assertTrue(json.contains("\"testSelectionNote\":\"Static call-path candidates.\""));
    }

    @Test
    void marksSourceLocationsUnavailableWithoutDroppingTheTestMapping() throws Exception {
        String methodId = "method:demo.Service#execute()V";
        String testId = "demo.FeatureTest#coversService";
        DependencyGraph graph = new DependencyGraph();
        graph.addNode(new GraphNode(methodId, GraphNode.Type.METHOD, Map.of(
                "className", "demo.Service",
                "methodName", "execute",
                "descriptor", "()V",
                "modulePath", temporaryDirectory.toString())));
        DependencyGraphResult result = new DependencyGraphResult(
                graph, List.of(methodId), List.of(), List.of(methodId), List.of(),
                List.of(methodId),
                List.of(new DependencyGraphResult.MethodTestCoverage(
                        methodId, "demo.FeatureTest", "coversService")),
                List.of(testId),
                List.of(new DependencyGraphResult.AffectedMethodTestGroup(
                        methodId, "DIRECT", List.of(testId))),
                List.of());

        String json = new DependencyGraphJsonExporter()
                .toJson(result, temporaryDirectory);

        assertTrue(json.contains("\"sourcePath\":null,\"lineNumber\":null,"
                + "\"locationStatus\":\"UNAVAILABLE\""));
        assertTrue(json.contains("\"testClass\":\"demo.FeatureTest\","
                + "\"testMethod\":\"coversService\""));
    }

    private static byte[] classWithMethod(
            String internalName, String sourceFile, String methodName,
            String descriptor, int sourceLine) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName,
                null, "java/lang/Object", null);
        writer.visitSource(sourceFile, null);
        var method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, methodName, descriptor, null, null);
        method.visitCode();
        Label start = new Label();
        method.visitLabel(start);
        method.visitLineNumber(sourceLine, start);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
