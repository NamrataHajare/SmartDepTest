package smartdeptest.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenArtifactResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesOldAndNewVersionsThroughTargetModulePom() throws Exception {
        Path project = createTargetProject();
        Path modulePom = project.resolve("module-a/pom.xml");
        List<Invocation> invocations = new ArrayList<>();
        MavenArtifactResolver resolver = new MavenArtifactResolver((workingDirectory, arguments) -> {
            invocations.add(new Invocation(workingDirectory, arguments));
            if (arguments.stream().anyMatch(argument -> argument.endsWith(":copy"))) {
                String coordinateText = argumentValue(arguments, "-Dartifact=");
                String[] coordinate = coordinateText.split(":");
                String version = coordinate[2];
                Path outputDirectory = Path.of(argumentValue(arguments, "-DoutputDirectory="));
                String source = "old-version".equals(version) ? "target-custom-repository" : "central";
                Path artifactPath = outputDirectory.resolve("sample-library-" + version + ".jar");
                Files.writeString(artifactPath, "synthetic jar", StandardCharsets.UTF_8);
                return "Downloaded from " + source + ": artifact-" + version;
            }

            Path output = Path.of(argumentValue(arguments, "-Dmdep.outputFile="));
            Files.writeString(output, "C:\\maven-cache\\sample-library.jar", StandardCharsets.UTF_8);
            return "classpath resolved";
        });

        MavenArtifactResolver.ResolvedArtifact oldArtifact = resolver.resolveJar(project, "module-a/pom.xml",
                "org.example", "sample-library", "old-version", "");
        MavenArtifactResolver.ResolvedArtifact newArtifact = resolver.resolveJar(project, "module-a/pom.xml",
                "org.example", "sample-library", "new-version", "");
        List<Path> oldClasspath = resolver.resolveApiClasspath(project, "module-a/pom.xml",
            "org.example", "sample-library", "old-version", "", oldArtifact);
        List<Path> classpath = resolver.resolveApiClasspath(project, "module-a/pom.xml",
                "org.example", "sample-library", "new-version", "", newArtifact);

        assertTrue(Files.isRegularFile(oldArtifact.jar()));
        assertTrue(Files.isRegularFile(newArtifact.jar()));
        assertTrue(oldArtifact.source().startsWith("target-custom-repository"));
        assertTrue(newArtifact.source().startsWith("central"));
        assertEquals(3, invocations.size());
        for (Invocation invocation : invocations) {
            assertEquals(project.toAbsolutePath().normalize(), invocation.workingDirectory());
            assertFalse(invocation.arguments().contains("-U"));
            int pomOption = invocation.arguments().indexOf("-f");
            assertEquals(modulePom.toAbsolutePath().normalize().toString(),
                    invocation.arguments().get(pomOption + 1));
        }
        assertTrue(invocations.get(0).arguments().stream().anyMatch(value -> value.contains("old-version")));
        assertTrue(invocations.get(1).arguments().stream().anyMatch(value -> value.contains("new-version")));
        assertTrue(invocations.get(0).arguments().stream().anyMatch(value -> value.startsWith("-Dartifact=")));
        assertTrue(invocations.get(0).arguments().stream().noneMatch(value -> value.contains("stripVersion")));
        assertTrue(invocations.get(2).arguments().stream().anyMatch(value -> value.endsWith(":build-classpath")));
        assertEquals(oldArtifact.jar(), oldClasspath.get(0));
        assertEquals(newArtifact.jar(), classpath.get(0));
    }

    @Test
    void retriesCachedMissingArtifactOnceWithMavenUpdateFlag() throws Exception {
        Path project = createTargetProject();
        List<Invocation> invocations = new ArrayList<>();
        MavenArtifactResolver resolver = new MavenArtifactResolver((workingDirectory, arguments) -> {
            invocations.add(new Invocation(workingDirectory, arguments));
            if (!arguments.contains("-U")) {
                throw new IOException("This failure was cached in the local repository after a previous attempt");
            }
            Path outputDirectory = Path.of(argumentValue(arguments, "-DoutputDirectory="));
            Files.writeString(outputDirectory.resolve("sample-library-1.0.jar"), "synthetic jar",
                    StandardCharsets.UTF_8);
            return "retried with update flag";
        });

        MavenArtifactResolver.ResolvedArtifact artifact = resolver.resolveJar(project, "module-a/pom.xml",
                "org.example", "sample-library", "1.0", "");

        assertEquals(2, invocations.size());
        assertFalse(invocations.get(0).arguments().contains("-U"));
        assertTrue(invocations.get(1).arguments().contains("-U"));
        assertTrue(Files.isRegularFile(artifact.jar()));
    }

    @Test
    void preservesMavenResolutionFailureAndCoordinate() throws Exception {
        Path project = createTargetProject();
        List<Invocation> invocations = new ArrayList<>();
        MavenArtifactResolver resolver = new MavenArtifactResolver((workingDirectory, arguments) -> {
            invocations.add(new Invocation(workingDirectory, arguments));
            assertEquals(project.toAbsolutePath().normalize(), workingDirectory);
            throw new IOException("[ERROR] Could not find artifact in target-custom-repository");
        });

        IOException exception = assertThrows(IOException.class, () -> resolver.resolveJar(project,
                "module-a/pom.xml", "org.example", "sample-library", "7.4.0", ""));

        assertTrue(exception.getMessage().contains("org.example:sample-library:7.4.0:jar"));
        assertTrue(exception.getMessage().contains("target-custom-repository"));
        assertTrue(exception.getMessage().contains("module-a\\pom.xml")
                || exception.getMessage().contains("module-a/pom.xml"));
        assertEquals(1, invocations.size());
        assertFalse(invocations.get(0).arguments().contains("-U"));
    }

    private Path createTargetProject() throws IOException {
        Path project = temporaryDirectory.resolve("target-project");
        Path module = project.resolve("module-a");
        Files.createDirectories(module);
        Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(module.resolve("pom.xml"),
                "<project><repositories><repository><id>target-custom-repository</id></repository>"
                        + "</repositories></project>", StandardCharsets.UTF_8);
        return project;
    }

    private static String argumentValue(List<String> arguments, String prefix) {
        return arguments.stream().filter(argument -> argument.startsWith(prefix))
                .map(argument -> argument.substring(prefix.length())).findFirst().orElseThrow();
    }

    private record Invocation(Path workingDirectory, List<String> arguments) {
        private Invocation {
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
            arguments = List.copyOf(arguments);
        }
    }
}