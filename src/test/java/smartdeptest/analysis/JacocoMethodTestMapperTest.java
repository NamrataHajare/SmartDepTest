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
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.io.InputStream;

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
    void mapsInheritedJUnit3TestsAcrossModulesAndCustomTestOutputPaths() throws Exception {
        Path appModule = temporaryDirectory.resolve("app");
        Path testsModule = temporaryDirectory.resolve("tests");
        Path applicationClasses = appModule.resolve("target/classes");
        Path customTestClasses = testsModule.resolve("target/custom-test-classes");
        Files.createDirectories(applicationClasses.resolve("demo"));
        Files.createDirectories(customTestClasses.resolve("fixture"));
        Files.writeString(temporaryDirectory.resolve("pom.xml"),
                "<project><modules><module>app</module><module>tests</module></modules></project>",
                StandardCharsets.UTF_8);
        Files.writeString(appModule.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(testsModule.resolve("pom.xml"),
                "<project><build><directory>target</directory>"
                        + "<testOutputDirectory>${project.build.directory}/custom-test-classes"
                        + "</testOutputDirectory></build></project>",
                StandardCharsets.UTF_8);

        String testClass = "fixture/FeatureTest";
        String baseClass = "fixture/BaseTest";
        writeTestClass(customTestClasses.resolve("fixture/BaseTest.class"),
                baseClass, "junit/framework/TestCase", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "demo/Service", Map.of("testInherited", "work()V"));
        writeTestClass(customTestClasses.resolve("fixture/FeatureTest.class"),
                testClass, baseClass, Opcodes.ACC_PUBLIC,
                "demo/Service", Map.of(
                        "testIndirect", "outer()V",
                        "testOverloaded", "work(I)V"));

        String work = methodId("demo.Service", "work", "()V");
        String overloadedWork = methodId("demo.Service", "work", "(I)V");
        String unmatchedOverload =
                methodId("demo.Service", "work", "(Ljava/lang/String;)V");
        String outer = methodId("demo.Service", "outer", "()V");
        DependencyGraph graph = new DependencyGraph();
        graph.addNode(moduleMethodNode(work, "demo.Service", "work", "()V", appModule));
        graph.addNode(moduleMethodNode(
                overloadedWork, "demo.Service", "work", "(I)V", appModule));
        graph.addNode(moduleMethodNode(
                unmatchedOverload, "demo.Service", "work",
                "(Ljava/lang/String;)V", appModule));
        graph.addNode(moduleMethodNode(outer, "demo.Service", "outer", "()V", appModule));
        graph.addEdge(outer, work, "CALLS", Map.of());
        DependencyGraphResult impact = new DependencyGraphResult(
                graph,
                List.of(),
                List.of(),
                List.of(work, overloadedWork, unmatchedOverload),
                List.of(outer),
                List.of(work, overloadedWork, unmatchedOverload, outer));

        JacocoMethodTestMapper.CoverageResult coverage =
                new JacocoMethodTestMapper().map(temporaryDirectory, impact);

        assertEquals(List.of("fixture.FeatureTest#testIndirect",
                        "fixture.FeatureTest#testInherited"),
                coverage.groupedSelectedTests().get(0).selectedTests());
        assertEquals(List.of("fixture.FeatureTest#testOverloaded"),
                coverage.groupedSelectedTests().get(1).selectedTests());
        assertEquals("NONE FOUND", coverage.groupedSelectedTests().get(2).selectionStatus());
        assertTrue(coverage.groupedSelectedTests().get(2).selectionNote()
                .contains("does not prove runtime non-coverage"));
        assertEquals(List.of("fixture.FeatureTest#testIndirect"),
                coverage.groupedSelectedTests().get(3).selectedTests());
        assertEquals("INDIRECT", coverage.groupedSelectedTests().get(3).impactType());
        assertEquals(3, coverage.selectedTests().size());
        assertTrue(coverage.selectionNote().contains("statically traceable"));
    }

    @Test
    void mapsARealTestMethodToTheExactApplicationMethod() throws Exception {
        Path applicationClasses = temporaryDirectory.resolve("target/classes");
        Path testClasses = temporaryDirectory.resolve("target/test-classes");
        Files.createDirectories(applicationClasses);
        Files.createDirectories(testClasses);
        Files.writeString(temporaryDirectory.resolve("pom.xml"),
                "<project><modules/></project>", StandardCharsets.UTF_8);
        copyClass(RealApplication.class, applicationClasses);
        copyClass(RealApplicationTest.class, testClasses);

        RealApplicationTest realTest = new RealApplicationTest();
        realTest.testRunsApplicationMethod();

        String method = methodId(
                RealApplication.class.getName(), "run", "()V");
        DependencyGraph graph = new DependencyGraph();
        graph.addNode(moduleMethodNode(
                method, RealApplication.class.getName(), "run", "()V",
                temporaryDirectory));
        DependencyGraphResult impact = new DependencyGraphResult(
                graph, List.of(), List.of(), List.of(method), List.of(), List.of(method));

        JacocoMethodTestMapper.CoverageResult coverage =
                new JacocoMethodTestMapper().map(temporaryDirectory, impact);

        assertEquals(List.of(
                RealApplicationTest.class.getName() + "#testRunsApplicationMethod"),
                coverage.selectedTests());
        assertEquals(method, coverage.methodTestCoverage().get(0).applicationMethod());
    }

    @Test
    void reportsNoStaticMatchInsteadOfCallingTheMethodRuntimeUncovered() throws Exception {
        Path applicationClasses = temporaryDirectory.resolve("target/classes");
        Path testClasses = temporaryDirectory.resolve("target/test-classes");
        Files.createDirectories(applicationClasses);
        Files.createDirectories(testClasses.resolve("fixture"));
        Files.writeString(temporaryDirectory.resolve("pom.xml"),
                "<project><modules/></project>", StandardCharsets.UTF_8);
        writeTestClass(testClasses.resolve("fixture/UnrelatedTest.class"),
                "fixture/UnrelatedTest", "junit/framework/TestCase", Opcodes.ACC_PUBLIC,
                "demo/Other", Map.of("testDifferentMethod", "()V"));

        String affected = methodId("demo.Service", "changed", "()V");
        DependencyGraph graph = new DependencyGraph();
        graph.addNode(moduleMethodNode(
                affected, "demo.Service", "changed", "()V", temporaryDirectory));
        DependencyGraphResult impact = new DependencyGraphResult(
                graph, List.of(), List.of(), List.of(affected), List.of(), List.of(affected));

        JacocoMethodTestMapper.CoverageResult coverage =
                new JacocoMethodTestMapper().map(temporaryDirectory, impact);

        assertEquals("NONE FOUND", coverage.groupedSelectedTests().get(0).selectionStatus());
        assertTrue(coverage.groupedSelectedTests().get(0).selectionNote()
                .contains("does not prove runtime non-coverage"));
        assertTrue(coverage.selectedTests().isEmpty());
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
        writeClass(project.resolve("target/classes/sample/Target.class"),
                "sample/Target", "java/lang/Object");

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

        assertFalse(Files.exists(project.resolve("target/test-classes")));
        assertTrue(coverage.failures().isEmpty());
        assertEquals(List.of("src/it/report-fixture#report"),
                coverage.groupedSelectedTests().get(0).selectedTests());
        assertEquals(List.of("src/it/report-fixture#report"), coverage.selectedTests());
        assertEquals("DIRECT", coverage.groupedSelectedTests().get(0).impactType());
        assertEquals("SELECTED", coverage.groupedSelectedTests().get(0).selectionStatus());
        assertEquals(1, coverage.methodTestCoverage().size());
        assertEquals(affectedMethod, coverage.methodTestCoverage().get(0).applicationMethod());
        assertTrue(coverage.selectionNote().contains("JaCoCo data is missing"));
    }

    private static GraphNode methodNode(String id, String className, String methodName, String descriptor) {
        return new GraphNode(id, GraphNode.Type.METHOD,
                Map.of("className", className, "methodName", methodName, "descriptor", descriptor));
    }

    private static GraphNode moduleMethodNode(
            String id, String className, String methodName, String descriptor, Path module) {
        return new GraphNode(id, GraphNode.Type.METHOD,
                Map.of(
                        "className", className,
                        "methodName", methodName,
                        "descriptor", descriptor,
                        "modulePath", module.toAbsolutePath().normalize().toString()));
    }

    private static String methodId(String className, String methodName, String descriptor) {
        String signature = className + "#" + methodName + descriptor;
        return "method:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signature.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeTestClass(
            Path path,
            String className,
            String superClass,
            int classAccess,
            String targetOwner,
            Map<String, String> testMethods) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, classAccess, className, null, superClass, null);
        for (Map.Entry<String, String> testMethod : testMethods.entrySet()) {
            String targetSignature = testMethod.getValue();
            int descriptorStart = targetSignature.indexOf('(');
            String targetName = targetSignature.substring(0, descriptorStart);
            String targetDescriptor = targetSignature.substring(descriptorStart);
            var method = writer.visitMethod(
                    Opcodes.ACC_PUBLIC, testMethod.getKey(), "()V", null, null);
            method.visitCode();
            if (targetDescriptor.startsWith("(I")) {
                method.visitInsn(Opcodes.ICONST_0);
            }
            method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    targetOwner,
                    targetName,
                    targetDescriptor,
                    false);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(targetDescriptor.startsWith("(I") ? 1 : 0, 1);
            method.visitEnd();
        }
        writer.visitEnd();
        Files.createDirectories(path.getParent());
        Files.write(path, writer.toByteArray());
    }

    private static void copyClass(Class<?> type, Path outputDirectory) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing compiled fixture class " + resource);
            }
            Path output = outputDirectory.resolve(
                    type.getName().replace('.', '/') + ".class");
            Files.createDirectories(output.getParent());
            Files.copy(input, output);
        }
    }

    private static void writeClass(Path path, String className, String superClass) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, superClass, null);
        writer.visitEnd();
        Files.write(path, writer.toByteArray());
    }

    static final class RealApplication {
        static void run() {
        }
    }

    static final class RealApplicationTest {
        @Test
        public void testRunsApplicationMethod() {
            RealApplication.run();
        }
    }
}
