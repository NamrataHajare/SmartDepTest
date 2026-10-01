package smartdeptest.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import smartdeptest.dependency.Dependency;
import smartdeptest.dependency.DependencyChange;
import smartdeptest.dependency.DependencyChangeResult;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisPipelineTest {
    private static final String GROUP_ID = "org.example";
    private static final String ARTIFACT_ID = "sample-library";

    @TempDir
    Path temporaryDirectory;

    @Test
    void removedMethodUsedByApplicationIsPotentialImpact() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", "package org.example; public class Service { "
                + "public void process(String value) {} public void keep() {} }");
        createLibraryJar(localRepository, "2.0", "package org.example; public class Service { "
                + "public void keep() {} }");
        Path project = createProject();
        writeSource(project, "module-a", "com.example.App",
                "package com.example; import org.example.Service; public class App { "
                        + "void create() { new Service().process(\"value\"); } }");

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project, List.of("module-a"));

        assertEquals(DependencyApiResult.Status.ANALYZED, apiChanges.dependencies().get(0).status());
        assertEquals("compile", apiChanges.dependencies().get(0).oldScope());
        assertEquals("compile", apiChanges.dependencies().get(0).newScope());
        assertFalse(apiChanges.dependencies().get(0).dependencyManagement());
        assertEquals("compile", usage.dependencies().get(0).oldScope());
        assertFalse(usage.dependencies().get(0).dependencyManagement());
        assertTrue(apiChanges.dependencies().get(0).changes().stream()
                .anyMatch(change -> change.kind() == ApiChange.Kind.METHOD_REMOVED));
        assertEquals(APIUsageResult.Classification.POTENTIAL_IMPACT,
                usage.dependencies().get(0).classification());
        APIUsageResult.UsageLocation location = usage.dependencies().get(0).findings().stream()
                .filter(APIUsageResult.UsageFinding::used).flatMap(finding -> finding.locations().stream())
                .findFirst().orElseThrow();
        assertEquals("com/example/App", location.className());
        assertEquals("create", location.methodName());
        assertEquals("()V", location.methodDescriptor());
        assertEquals("INVOKEVIRTUAL", location.instructionType());
    }

    @Test
    void inheritedRemovedMethodIsMatchedThroughSubclassOwner() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", Map.of(
                "org/example/Base.java", "package org.example; public class Base { public void removed() {} }",
                "org/example/Child.java", "package org.example; public class Child extends Base {}"));
        createLibraryJar(localRepository, "2.0", Map.of(
                "org/example/Base.java", "package org.example; public class Base {}",
                "org/example/Child.java", "package org.example; public class Child extends Base {}"));
        Path project = createProject();
        Path module = project.resolve("module-a");
        Path classes = module.resolve("target/classes");
        Files.createDirectories(classes.resolve("com/example"));
        Files.writeString(module.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        writeSubclassOwnerCall(classes.resolve("com/example/App.class"));

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project, List.of("module-a"));

        APIUsageResult.UsageFinding finding = usage.dependencies().get(0).findings().stream()
                .filter(APIUsageResult.UsageFinding::used).findFirst().orElseThrow();
        assertEquals(ApiChange.Kind.METHOD_REMOVED, finding.change().kind());
        assertEquals("org/example/Base", finding.change().className());
        assertEquals("com/example/App", finding.locations().get(0).className());
        assertEquals("INVOKEVIRTUAL", finding.locations().get(0).instructionType());
    }

    @Test
    void subclassOverrideIsNotMatchedToRemovedSuperclassMethod() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", Map.of(
                "org/example/Base.java", "package org.example; public class Base { public void removed() {} }",
                "org/example/Child.java", "package org.example; public class Child extends Base { "
                        + "@Override public void removed() {} }"));
        createLibraryJar(localRepository, "2.0", Map.of(
                "org/example/Base.java", "package org.example; public class Base {}",
                "org/example/Child.java", "package org.example; public class Child extends Base { "
                        + "public void removed() {} }"));
        Path project = createProject();
        Path module = project.resolve("module-a");
        Path classes = module.resolve("target/classes");
        Files.createDirectories(classes.resolve("com/example"));
        Files.writeString(module.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        writeSubclassOwnerCall(classes.resolve("com/example/App.class"));

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project, List.of("module-a"));

        assertEquals(APIUsageResult.Classification.NO_IDENTIFIED_IMPACT,
                usage.dependencies().get(0).classification());
    }

    @Test
    void removedMethodNotUsedByApplicationHasNoIdentifiedImpact() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", "package org.example; public class Service { "
                + "public void obsolete() {} public void keep() {} }");
        createLibraryJar(localRepository, "2.0", "package org.example; public class Service { "
                + "public void keep() {} }");
        Path project = createProject();
        writeSource(project, "module-a", "com.example.App",
                "package com.example; import org.example.Service; public class App { "
                        + "void create() { new Service().keep(); } }");

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project);

        assertEquals(APIUsageResult.Classification.NO_IDENTIFIED_IMPACT,
                usage.dependencies().get(0).classification());
    }

    @Test
    void addedMethodIsReportedButDoesNotCreateImpact() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", "package org.example; public class Service { }");
        createLibraryJar(localRepository, "2.0", "package org.example; public class Service { "
                + "public void newlyAdded() {} }");
        Path project = createProject();
        writeSource(project, "module-a", "com.example.App",
                "package com.example; public class App { void call() {} }");

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project);

        assertTrue(apiChanges.dependencies().get(0).changes().stream()
                .anyMatch(change -> change.kind() == ApiChange.Kind.METHOD_ADDED));
        assertEquals(APIUsageResult.Classification.NO_IDENTIFIED_IMPACT,
                usage.dependencies().get(0).classification());
    }

    @Test
    void addedAbstractInterfaceMethodImpactsApplicationImplementation() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", "package org.example; public interface Service { "
                + "void keep(); }");
        createLibraryJar(localRepository, "2.0", "package org.example; public interface Service { "
                + "void keep(); void newlyRequired(); }");
        Path project = createProject();
        writeSource(project, "module-a", "com.example.App",
                "package com.example; import org.example.Service; public class App implements Service { "
                        + "public void keep() {} }");

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project, List.of("module-a"));

        APIUsageResult.UsageFinding addedContract = usage.dependencies().get(0).findings().stream()
                .filter(finding -> finding.change().kind() == ApiChange.Kind.METHOD_ADDED)
                .findFirst().orElseThrow();
        assertTrue(addedContract.change().potentiallyIncompatible());
        assertTrue(addedContract.used());
        assertEquals(APIUsageResult.Classification.POTENTIAL_IMPACT,
                usage.dependencies().get(0).classification());
    }

    @Test
    void reportsOnlyModuleThatUsesRemovedApi() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", "package org.example; public class Service { "
                + "public void process() {} }");
        createLibraryJar(localRepository, "2.0", "package org.example; public class Service { }");
        Path project = createProject();
        writeSource(project, "module-a", "com.example.UsingApp",
                "package com.example; import org.example.Service; public class UsingApp { "
                        + "void call() { new Service().process(); } }");
        writeSource(project, "module-b", "com.example.OtherApp",
                "package com.example; import org.example.Service; public class OtherApp { "
                        + "void call() { new Service(); } }");

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project, List.of("module-a"));

        List<APIUsageResult.UsageLocation> locations = usage.dependencies().get(0).findings().stream()
                .flatMap(finding -> finding.locations().stream()).toList();
        assertEquals(APIUsageResult.Classification.POTENTIAL_IMPACT,
                usage.dependencies().get(0).classification());
        assertEquals(1, locations.size());
        assertEquals("com/example/UsingApp", locations.get(0).className());
    }

    @Test
    void changedMethodSignatureUsedByApplicationIsPotentialImpact() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", "package org.example; public class Service { "
                + "public void process(String value) {} }");
        createLibraryJar(localRepository, "2.0", "package org.example; public class Service { "
                + "public void process(String value, int mode) {} }");
        Path project = createProject();
        writeSource(project, "module-a", "com.example.App",
                "package com.example; import org.example.Service; public class App { "
                        + "void call() { new Service().process(\"value\"); } }");

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project);

        assertTrue(apiChanges.dependencies().get(0).changes().stream()
                .anyMatch(change -> change.kind() == ApiChange.Kind.METHOD_MODIFIED));
        ApiChange signatureChange = apiChanges.dependencies().get(0).changes().stream()
                .filter(change -> change.kind() == ApiChange.Kind.METHOD_MODIFIED).findFirst().orElseThrow();
        assertEquals("(Ljava/lang/String;)V", signatureChange.oldDescriptor());
        assertEquals("(Ljava/lang/String;I)V", signatureChange.newDescriptor());
        assertEquals(APIUsageResult.Classification.POTENTIAL_IMPACT,
                usage.dependencies().get(0).classification());
    }

    @Test
    void overloadedMethodsAreMatchedByJvmDescriptor() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", "package org.example; public class Service { "
                + "public void foo(int value) {} public void foo(String value) {} }");
        createLibraryJar(localRepository, "2.0", "package org.example; public class Service { "
                + "public void foo(long value) {} public void foo(String value) {} }");
        Path project = createProject();
        writeSource(project, "module-a", "com.example.App",
                "package com.example; import org.example.Service; public class App { "
                        + "void callBoth() { Service service = new Service(); service.foo(1); service.foo(\"x\"); } }");

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project);

        List<APIUsageResult.UsageFinding> used = usage.dependencies().get(0).findings().stream()
                .filter(APIUsageResult.UsageFinding::used).toList();
        assertEquals(1, used.size());
        assertEquals(ApiChange.Kind.METHOD_MODIFIED, used.get(0).change().kind());
        assertEquals("(I)V", used.get(0).change().oldDescriptor());
        assertEquals("(J)V", used.get(0).change().newDescriptor());
        assertEquals("callBoth", used.get(0).locations().get(0).methodName());
    }

    @Test
    void changedFieldAccessIsReported() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", "package org.example; public class Service { public int count; }");
        createLibraryJar(localRepository, "2.0", "package org.example; public class Service { public long count; }");
        Path project = createProject();
        writeSource(project, "module-a", "com.example.App",
                "package com.example; import org.example.Service; public class App { "
                        + "int read() { return new Service().count; } }");

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project);

        APIUsageResult.UsageFinding finding = usage.dependencies().get(0).findings().stream()
                .filter(APIUsageResult.UsageFinding::used).findFirst().orElseThrow();
        assertEquals(ApiChange.Kind.FIELD_MODIFIED, finding.change().kind());
        assertEquals("I", finding.change().oldDescriptor());
        assertEquals("J", finding.change().newDescriptor());
        assertEquals("GETFIELD", finding.locations().get(0).instructionType());
        assertEquals("read", finding.locations().get(0).methodName());
    }

    @Test
    void multipleApplicationMethodsUsingChangedApiAreReported() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", "package org.example; public class Service { public void run() {} }");
        createLibraryJar(localRepository, "2.0", "package org.example; public class Service { }");
        Path project = createProject();
        writeSource(project, "module-a", "com.example.App",
                "package com.example; import org.example.Service; public class App { "
                        + "void first() { new Service().run(); } void second() { new Service().run(); } }");

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project);

        List<String> methods = usage.dependencies().get(0).findings().stream()
                .filter(APIUsageResult.UsageFinding::used).flatMap(finding -> finding.locations().stream())
                .map(APIUsageResult.UsageLocation::methodName).distinct().sorted().toList();
        assertEquals(List.of("first", "second"), methods);
    }

    @Test
    void reportsSeveralChangedApisIndividuallyWhenOnlyOneIsUsed() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", "package org.example; public class Service { "
                + "public void usedBefore() {} public void unusedBefore() {} }");
        createLibraryJar(localRepository, "2.0", "package org.example; public class Service { }");
        Path project = createProject();
        writeSource(project, "module-a", "com.example.App",
                "package com.example; import org.example.Service; public class App { "
                        + "void call() { new Service().usedBefore(); } }");

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project);

        List<APIUsageResult.UsageFinding> findings = usage.dependencies().get(0).findings();
        assertEquals(2, findings.size());
        assertEquals(1, findings.stream().filter(APIUsageResult.UsageFinding::used).count());
        assertEquals(APIUsageResult.Classification.POTENTIAL_IMPACT,
                usage.dependencies().get(0).classification());
    }

    @Test
    void missingArtifactIsUnavailableRatherThanNoImpact() throws Exception {
        Path project = createProject();
        APIChangeResult apiChanges = analyzeApiChange(localRepository(), project, "1.0", "2.0");

        assertEquals(DependencyApiResult.Status.UNAVAILABLE, apiChanges.dependencies().get(0).status());
        assertEquals(APIUsageResult.Classification.ANALYSIS_UNAVAILABLE,
                analyzeUsage(localRepository(), apiChanges, project).dependencies().get(0).classification());
    }

        @Test
        void missingTargetClassesIsUnavailableRatherThanNoImpact() throws Exception {
                Path localRepository = localRepository();
                createLibraryJar(localRepository, "1.0", "package org.example; public class Service { public void old() {} }");
                createLibraryJar(localRepository, "2.0", "package org.example; public class Service { }");
                Path project = createProject();
                writeSource(project, "module-a", "com.example.App",
                                "package com.example; public class App { void call() {} }");
                APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
                Path updatedJar = localRepository.resolve("org/example/sample-library/2.0/sample-library-2.0.jar");
                MavenModuleClasspathResolver classpath = new MavenModuleClasspathResolver() {
                        @Override
                        List<Path> resolve(ApplicationModule module) {
                                return List.of(updatedJar);
                        }
                };

                APIUsageResult usage = new APIUsageAnalyzer(classpath).analyze(apiChanges, project);

                assertEquals(APIUsageResult.Classification.ANALYSIS_UNAVAILABLE,
                                usage.dependencies().get(0).classification());
                assertTrue(usage.dependencies().get(0).message().contains("target"),
                        usage.dependencies().get(0).message());
        }

        @Test
        void dependencyManagementMetadataSurvivesApiImpactAnalysis() throws Exception {
                Path localRepository = localRepository();
                createLibraryJar(localRepository, "1.0", "package org.example; public class Service { "
                                + "public void old() {} public void keep() {} }");
                createLibraryJar(localRepository, "2.0", "package org.example; public class Service { public void keep() {} }");
                Path project = createProject();
                writeSource(project, "module-a", "com.example.App",
                                "package com.example; import org.example.Service; public class App { "
                                                + "void call() { new Service().keep(); } }");
                Dependency oldDependency = dependency("1.0", "runtime", true);
                Dependency newDependency = dependency("2.0", "compile", true);

                APIChangeResult apiChanges = analyzeApiChange(localRepository, project, oldDependency, newDependency);
                APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project, List.of("module-a"));

                assertTrue(apiChanges.dependencies().get(0).dependencyManagement());
                assertEquals("runtime", apiChanges.dependencies().get(0).oldScope());
                assertEquals("compile", apiChanges.dependencies().get(0).newScope());
                assertTrue(usage.dependencies().get(0).dependencyManagement());
                assertEquals("runtime", usage.dependencies().get(0).oldScope());
                assertEquals("compile", usage.dependencies().get(0).newScope());
        }

        @Test
        void managedAndDirectRecordsForSameUpdateAreComparedOnce() throws Exception {
                Path localRepository = localRepository();
                createLibraryJar(localRepository, "1.0", "package org.example; public class Service { public void old() {} }");
                createLibraryJar(localRepository, "2.0", "package org.example; public class Service { }");
                Path project = createProject();
                Dependency oldManaged = dependency("1.0", "compile", true);
                Dependency newManaged = dependency("2.0", "compile", true);
                Dependency oldDirect = dependency("1.0", "compile", false);
                Dependency newDirect = dependency("2.0", "compile", false);
                List<DependencyChange> changes = List.of(
                                new DependencyChange(DependencyChange.Type.UPDATED, oldManaged, newManaged,
                                                "pom.xml", "candidate", "parent"),
                                new DependencyChange(DependencyChange.Type.UPDATED, oldDirect, newDirect,
                                                "pom.xml", "candidate", "parent"));
                DependencyChangeResult dependencyChanges = new DependencyChangeResult(project.toString(),
                                "candidate", "parent", "test update", List.of("pom.xml"), changes);
                MavenArtifactResolver offlineResolver = new MavenArtifactResolver() {
                        @Override
                        ResolvedArtifact resolveJar(Path projectDirectory, String pomPath, String groupId,
                                                                                String artifactId, String version, String classifier) throws IOException {
                                Path jar = localRepository.resolve(groupId.replace('.', '/')).resolve(artifactId)
                                                .resolve(version).resolve(artifactId + "-" + version + ".jar");
                                if (!Files.isRegularFile(jar)) throw new IOException("Test fixture artifact missing: " + jar);
                                return new ResolvedArtifact(jar, "synthetic local test repository");
                        }

                        @Override
                        List<Path> resolveApiClasspath(Path projectDirectory, String pomPath, String groupId,
                                                                                   String artifactId, String version, String classifier,
                                                                                   ResolvedArtifact dependencyArtifact) {
                                return List.of(dependencyArtifact.jar());
                        }
                };

                APIChangeResult result = new APIChangeAnalyzer(offlineResolver).analyze(dependencyChanges);

                assertEquals(1, result.dependencies().size());
                assertFalse(result.dependencies().get(0).dependencyManagement());
                assertEquals(1, result.dependencies().get(0).changes().size());
        }

    @Test
        void addedApiUsedByApplicationIsDetectedInBytecode() throws Exception {
        Path localRepository = localRepository();
        createLibraryJar(localRepository, "1.0", "package org.example; public class Service { "
                + "public void removedBefore() {} }");
        createLibraryJar(localRepository, "2.0", "package org.example; public class Service { "
                + "public void addedNow() {} }");
        Path project = createProject();
        writeSource(project, "module-a", "com.example.App",
                "package com.example; import org.example.Service; public class App { "
                        + "void call() { new Service().addedNow(); } }");

        APIChangeResult apiChanges = analyzeApiChange(localRepository, project, "1.0", "2.0");
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project,
                List.of("module-a"), Path.of(apiChanges.dependencies().get(0).newArtifactPath()));

        assertEquals(APIUsageResult.Classification.POTENTIAL_IMPACT,
                usage.dependencies().get(0).classification());
    }

    private APIChangeResult analyzeApiChange(Path localRepository, Path project,
                                             String oldVersion, String newVersion) {
        Dependency oldDependency = dependency(oldVersion);
        Dependency newDependency = dependency(newVersion);
                return analyzeApiChange(localRepository, project, oldDependency, newDependency);
        }

        private APIChangeResult analyzeApiChange(Path localRepository, Path project,
                                                                                         Dependency oldDependency, Dependency newDependency) {
        DependencyChange change = new DependencyChange(DependencyChange.Type.UPDATED, oldDependency,
                newDependency, "pom.xml", "candidate", "parent");
        DependencyChangeResult dependencyChanges = new DependencyChangeResult(project.toString(),
                "candidate", "parent", "test update", List.of("pom.xml"), List.of(change));
                MavenArtifactResolver offlineResolver = new MavenArtifactResolver() {
                        @Override
                        ResolvedArtifact resolveJar(Path projectDirectory, String pomPath, String groupId,
                                                                                String artifactId, String version, String classifier) throws IOException {
                                Path jar = localRepository.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version)
                                                .resolve(artifactId + "-" + version
                                                                + (classifier == null || classifier.isBlank() ? "" : "-" + classifier) + ".jar");
                                if (!Files.isRegularFile(jar)) throw new IOException("Test fixture artifact missing: " + jar);
                                return new ResolvedArtifact(jar, "synthetic local test repository");
                        }

                        @Override
                        List<Path> resolveApiClasspath(Path projectDirectory, String pomPath, String groupId,
                                                                                   String artifactId, String version, String classifier,
                                                                                   ResolvedArtifact dependencyArtifact) {
                                return List.of(dependencyArtifact.jar());
                        }
                };
                return new APIChangeAnalyzer(offlineResolver).analyze(dependencyChanges);
    }

    private APIUsageResult analyzeUsage(Path localRepository, APIChangeResult apiChanges, Path project)
            throws IOException {
                return analyzeUsage(localRepository, apiChanges, project, List.of("module-a", "module-b"));
        }

        private APIUsageResult analyzeUsage(Path localRepository, APIChangeResult apiChanges,
                                                                                Path project, List<String> dependencyModules) throws IOException {
                DependencyApiResult dependency = apiChanges.dependencies().get(0);
                return analyzeUsage(localRepository, apiChanges, project, dependencyModules,
                                dependency.oldArtifactPath().isBlank() ? null : Path.of(dependency.oldArtifactPath()));
        }

        private APIUsageResult analyzeUsage(Path localRepository, APIChangeResult apiChanges,
                                                                                Path project, List<String> dependencyModules,
                                                                                Path compilationJar) throws IOException {
                DependencyApiResult dependency = apiChanges.dependencies().get(0);
                if (compilationJar != null) compileApplicationClasses(project, dependencyModules, compilationJar);
                Path updatedJar = localRepository.resolve(GROUP_ID.replace('.', '/')).resolve(ARTIFACT_ID)
                                .resolve(dependency.newVersion()).resolve(ARTIFACT_ID + "-" + dependency.newVersion() + ".jar");
        MavenModuleClasspathResolver noMavenResolution = new MavenModuleClasspathResolver() {
            @Override
            List<Path> resolve(ApplicationModule module) {
                                return dependencyModules.contains(module.moduleDirectory().getFileName().toString())
                                                ? List.of(updatedJar) : List.of();
            }
        };
        return new APIUsageAnalyzer(noMavenResolution).analyze(apiChanges, project);
    }

        private void compileApplicationClasses(Path project, List<String> modules, Path dependencyJar) throws IOException {
                var compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null) throw new IOException("A JDK is required to compile application fixtures.");
                for (String module : modules) {
                        Path sourceRoot = project.resolve(module).resolve("src/main/java");
                        if (!Files.isDirectory(sourceRoot)) continue;
                        List<Path> sourceFiles;
                        try (var files = Files.walk(sourceRoot)) {
                                sourceFiles = files.filter(Files::isRegularFile)
                                                .filter(path -> path.getFileName().toString().endsWith(".java")).toList();
                        }
                        if (sourceFiles.isEmpty()) continue;
                        Path output = project.resolve(module).resolve("target/classes");
                        Files.createDirectories(output);
                        List<String> arguments = new java.util.ArrayList<>(List.of("-proc:none", "-classpath",
                                        dependencyJar.toString(), "-d", output.toString()));
                        sourceFiles.forEach(path -> arguments.add(path.toString()));
                        int exitCode = compiler.run(null, null, null, arguments.toArray(String[]::new));
                        if (exitCode != 0) throw new IOException("Unable to compile application fixture module " + module);
                }
        }

    private Dependency dependency(String version) {
                return dependency(version, "compile", false);
        }

        private Dependency dependency(String version, String scope, boolean managed) {
                return new Dependency(GROUP_ID, ARTIFACT_ID, version, scope, "jar", "", false,
                                managed, "pom.xml");
    }

        private Path localRepository() throws IOException {
                Path repository = Files.createTempDirectory("smartdeptest-test-m2-");
                repository.toFile().deleteOnExit();
                return repository;
    }

    private Path createProject() throws IOException {
        Path project = temporaryDirectory.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        return project;
    }

    private void writeSource(Path project, String module, String qualifiedName, String source) throws IOException {
        Path moduleRoot = project.resolve(module);
        Files.createDirectories(moduleRoot);
        Files.writeString(moduleRoot.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Path sourceFile = moduleRoot.resolve("src/main/java")
                .resolve(qualifiedName.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
    }

    private void createLibraryJar(Path repository, String version, String source) throws IOException {
                createLibraryJar(repository, version, Map.of("org/example/Service.java", source));
        }

        private void createLibraryJar(Path repository, String version, Map<String, String> sources) throws IOException {
        Path work = temporaryDirectory.resolve("library-" + version);
                Path sourceDirectory = work.resolve("src");
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
                List<Path> sourceFiles = new java.util.ArrayList<>();
                for (Map.Entry<String, String> source : sources.entrySet()) {
                        Path sourceFile = sourceDirectory.resolve(source.getKey());
                        Files.createDirectories(sourceFile.getParent());
                        Files.writeString(sourceFile, source.getValue(), StandardCharsets.UTF_8);
                        sourceFiles.add(sourceFile);
                }
                List<String> arguments = new java.util.ArrayList<>(List.of("-d", classes.toString()));
                sourceFiles.forEach(path -> arguments.add(path.toString()));
                int status = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                                arguments.toArray(String[]::new));
        if (status != 0) throw new IOException("Unable to compile test dependency fixture.");

        Path jar = repository.resolve("org/example/sample-library/" + version
                + "/sample-library-" + version + ".jar");
        Files.createDirectories(jar.getParent());
        Path directory = repository;
        for (Path segment : repository.relativize(jar.getParent())) {
            directory = directory.resolve(segment);
            directory.toFile().deleteOnExit();
        }
        jar.toFile().deleteOnExit();
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
             var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String entryName = classes.relativize(file).toString().replace('\\', '/');
                output.putNextEntry(new JarEntry(entryName));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

        private static void writeSubclassOwnerCall(Path classFile) throws IOException {
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/App", null,
                                "java/lang/Object", null);
                MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "call", "()V", null, null);
                method.visitCode();
                method.visitTypeInsn(Opcodes.NEW, "org/example/Child");
                method.visitInsn(Opcodes.DUP);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/example/Child", "<init>", "()V", false);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/example/Child", "removed", "()V", false);
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(2, 1);
                method.visitEnd();
                writer.visitEnd();
                Files.write(classFile, writer.toByteArray());
        }
}