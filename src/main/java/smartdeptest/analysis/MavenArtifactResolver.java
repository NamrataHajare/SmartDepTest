package smartdeptest.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class MavenArtifactResolver {
    @FunctionalInterface
    interface MavenInvoker {
        String run(Path workingDirectory, List<String> arguments) throws IOException;
    }

    record ResolvedArtifact(Path jar, String source) {}

    private static final Pattern TRANSFER_SOURCE = Pattern.compile(
            "(?m)^.*(?:Downloaded|Downloading) from ([^\\s:]+): (\\S+).*$");
        private static final List<String> CACHED_MISS_MARKERS = List.of(
            "failure was cached", "cached in the local repository", "previous attempt",
            "will not be reattempted", "not reattempted until");
    private final MavenInvoker mavenInvoker;
    private final Map<String, ResolvedArtifact> resolved = new HashMap<>();
    private final Map<String, List<Path>> apiClasspaths = new HashMap<>();

    MavenArtifactResolver() {
        this(MavenCommandRunner::run);
    }

    MavenArtifactResolver(MavenInvoker mavenInvoker) {
        this.mavenInvoker = mavenInvoker;
    }

    ResolvedArtifact resolveJar(Path projectDirectory, String pomPath, String groupId,
                                String artifactId, String version, String classifier) throws IOException {
        String effectiveClassifier = classifier == null ? "" : classifier;
        String coordinate = groupId + ":" + artifactId + ":" + version + ":jar"
                + (effectiveClassifier.isBlank() ? "" : ":" + effectiveClassifier);
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Path projectPom = projectRoot.resolve(pomPath).normalize();
        if (!projectPom.startsWith(projectRoot) || !Files.isRegularFile(projectPom)) {
            throw new IOException("Target Maven POM does not exist: " + projectPom);
        }

        String cacheKey = projectPom + "|" + coordinate;
        ResolvedArtifact cached = resolved.get(cacheKey);
        if (cached != null && Files.isRegularFile(cached.jar())) return cached;
        if (!validCoordinatePart(groupId) || !validCoordinatePart(artifactId) || !validCoordinatePart(version)
                || (!effectiveClassifier.isBlank() && !validCoordinatePart(effectiveClassifier))) {
            throw new IOException("Dependency coordinates are incomplete or contain an unresolved Maven property: "
                    + coordinate);
        }

        Path destinationDirectory = Files.createTempDirectory("smartdeptest-resolved-artifact-");
        destinationDirectory.toFile().deleteOnExit();
        Path jar = destinationDirectory.resolve(artifactId + "-" + version
                + (effectiveClassifier.isBlank() ? "" : "-" + effectiveClassifier) + ".jar");
        jar.toFile().deleteOnExit();
        try {
                String artifactCoordinate = groupId + ":" + artifactId + ":" + version + ":jar"
                    + (effectiveClassifier.isBlank() ? "" : ":" + effectiveClassifier);
                String output = runWithCachedMissRetry(mavenInvoker, projectRoot, List.of("mvn", "-f", projectPom.toString(),
                    "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:copy",
                    "-Dartifact=" + artifactCoordinate, "-DoutputDirectory=" + destinationDirectory));
            if (!Files.isRegularFile(jar)) {
                throw new IOException("Maven completed without producing the requested JAR at " + jar
                        + ". Maven output: " + output);
            }
            String source = repositorySource(output);
            ResolvedArtifact artifact = new ResolvedArtifact(jar, source);
            resolved.put(cacheKey, artifact);
            return artifact;
        } catch (IOException exception) {
            throw new IOException("Could not resolve " + coordinate + " using target project's Maven POM "
                    + projectPom + ". Maven error: " + exception.getMessage(), exception);
        }
    }

    List<Path> resolveApiClasspath(Path projectDirectory, String pomPath, String groupId,
                                   String artifactId, String version, String classifier,
                                   ResolvedArtifact dependencyArtifact) throws IOException {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Path projectPom = projectRoot.resolve(pomPath).normalize();
        String cacheKey = projectPom.toString();
        List<Path> baseClasspath = apiClasspaths.get(cacheKey);

        if (baseClasspath == null) {
            Path output = Files.createTempFile("smartdeptest-api-classpath-", ".txt");
            try {
                List<String> entries = new ArrayList<>();
                entries.add("mvn");
                entries.add("-f");
                entries.add(projectPom.toString());
                entries.add("org.apache.maven.plugins:maven-dependency-plugin:3.7.1:build-classpath");
                entries.add("-DincludeScope=compile");
                entries.add("-Dmdep.outputFile=" + output);
                runWithCachedMissRetry(mavenInvoker, projectRoot, entries);

                List<Path> classpath = new ArrayList<>();
                if (Files.isRegularFile(output)) {
                    String value = Files.readString(output).trim();
                    if (!value.isBlank()) {
                        for (String entry : value.split(Pattern.quote(java.io.File.pathSeparator))) {
                            Path path = Path.of(entry);
                            if (Files.exists(path)) classpath.add(path.toAbsolutePath().normalize());
                        }
                    }
                }
                baseClasspath = classpath.stream().distinct().toList();
                apiClasspaths.put(cacheKey, baseClasspath);
            } catch (IOException exception) {
                throw new IOException("Could not resolve the API classpath for " + groupId + ":" + artifactId + ":"
                        + version + " using target project's Maven POM " + projectPom + ". Maven error: "
                        + exception.getMessage(), exception);
            } finally {
                Files.deleteIfExists(output);
            }
        }

        List<Path> classpath = new ArrayList<>(baseClasspath);
        classpath.add(dependencyArtifact.jar().toAbsolutePath().normalize());
        return classpath.stream().distinct().toList();
    }

    static String runWithCachedMissRetry(MavenInvoker mavenInvoker, Path workingDirectory,
                                         List<String> arguments) throws IOException {
        try {
            return mavenInvoker.run(workingDirectory, arguments);
        } catch (IOException firstFailure) {
            String message = firstFailure.getMessage();
            if (message == null || CACHED_MISS_MARKERS.stream()
                    .noneMatch(marker -> message.toLowerCase(java.util.Locale.ROOT).contains(marker))) {
                throw firstFailure;
            }
            List<String> retryArguments = new ArrayList<>(arguments);
            int mavenIndex = retryArguments.indexOf("mvn");
            retryArguments.add(mavenIndex >= 0 ? mavenIndex + 1 : 0, "-U");
            try {
                return mavenInvoker.run(workingDirectory, retryArguments);
            } catch (IOException retryFailure) {
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        }
    }

    private static String repositorySource(String mavenOutput) {
        Matcher matcher = TRANSFER_SOURCE.matcher(mavenOutput);
        String lastSource = null;
        while (matcher.find()) {
            lastSource = matcher.group(1) + " (" + matcher.group(2) + ")";
        }
        return lastSource == null
                ? "Maven local cache or target project's configured repositories/settings (Maven-selected)"
                : lastSource;
    }

    private static boolean validCoordinatePart(String value) {
        return value != null && value.matches("[A-Za-z0-9_.+-]+")
                && !value.contains("${") && !value.isBlank();
    }

    static Path artifactPathSuffix(String groupId, String artifactId, String version, String classifier) {
        String fileName = artifactId + "-" + version
                + (classifier == null || classifier.isBlank() ? "" : "-" + classifier) + ".jar";
        Path groupPath = Path.of(groupId.replace('.', java.io.File.separatorChar));
        return groupPath.resolve(artifactId).resolve(version).resolve(fileName);
    }
}
