package smartdeptest.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import smartdeptest.dependency.Dependency;
import smartdeptest.dependency.DependencyChange;
import smartdeptest.dependency.DependencyChangeResult;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(apiChanges.dependencies().get(0).changes().stream()
                .anyMatch(change -> change.kind() == ApiChange.Kind.METHOD_REMOVED));
        assertEquals(APIUsageResult.Classification.POTENTIAL_IMPACT,
                usage.dependencies().get(0).classification());
        APIUsageResult.UsageLocation location = usage.dependencies().get(0).findings().stream()
                .filter(APIUsageResult.UsageFinding::used).flatMap(finding -> finding.locations().stream())
                .findFirst().orElseThrow();
        assertEquals("module-a/src/main/java/com/example/App.java", location.sourcePath());
        assertEquals("create", location.methodName());
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
        assertTrue(locations.get(0).sourcePath().startsWith("module-a/"));
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
        assertEquals(APIUsageResult.Classification.POTENTIAL_IMPACT,
                usage.dependencies().get(0).classification());
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
    void unresolvedNewVersionApiDoesNotBecomeNoImpact() throws Exception {
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
        APIUsageResult usage = analyzeUsage(localRepository, apiChanges, project);

        assertEquals(APIUsageResult.Classification.ANALYSIS_UNAVAILABLE,
                usage.dependencies().get(0).classification());
    }

    private APIChangeResult analyzeApiChange(Path localRepository, Path project,
                                             String oldVersion, String newVersion) {
        Dependency oldDependency = dependency(oldVersion);
        Dependency newDependency = dependency(newVersion);
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

    private APIUsageResult analyzeUsage(Path localRepository, APIChangeResult apiChanges, Path project) {
                return analyzeUsage(localRepository, apiChanges, project, List.of("module-a", "module-b"));
        }

        private APIUsageResult analyzeUsage(Path localRepository, APIChangeResult apiChanges,
                                                                                Path project, List<String> dependencyModules) {
                DependencyApiResult dependency = apiChanges.dependencies().get(0);
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

    private Dependency dependency(String version) {
        return new Dependency(GROUP_ID, ARTIFACT_ID, version, "compile", "jar", "", false,
                false, "pom.xml");
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
        Path work = temporaryDirectory.resolve("library-" + version);
        Path sourceFile = work.resolve("src/org/example/Service.java");
        Path classes = work.resolve("classes");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classes);
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), sourceFile.toString());
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
}