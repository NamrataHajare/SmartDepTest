package smartdeptest.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class MavenArtifactResolver {
    private final Path localRepository;
    private final Map<String, Path> resolved = new HashMap<>();
    private final Map<String, List<Path>> apiClasspaths = new HashMap<>();

    MavenArtifactResolver() {
        this(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    MavenArtifactResolver(Path localRepository) {
        this.localRepository = localRepository.toAbsolutePath().normalize();
    }

    Path resolveJar(String groupId, String artifactId, String version) throws IOException {
        return resolveJar(groupId, artifactId, version, "");
        }

        Path resolveJar(String groupId, String artifactId, String version, String classifier) throws IOException {
        String effectiveClassifier = classifier == null ? "" : classifier;
        String coordinate = groupId + ":" + artifactId + ":" + version + ":jar"
            + (effectiveClassifier.isBlank() ? "" : ":" + effectiveClassifier);
        Path cached = resolved.get(coordinate);
        if (cached != null && Files.isRegularFile(cached)) return cached;
        if (!validCoordinatePart(groupId) || !validCoordinatePart(artifactId) || !validCoordinatePart(version)
            || (!effectiveClassifier.isBlank() && !validCoordinatePart(effectiveClassifier))) {
            throw new IOException("Dependency coordinates are incomplete or contain an unresolved Maven property: "
                    + coordinate);
        }

        Path jar = expectedJarPath(groupId, artifactId, version, effectiveClassifier);
        if (!Files.isRegularFile(jar)) {
            MavenCommandRunner.run(Path.of(System.getProperty("user.dir")), List.of(
                    "mvn", "-q", "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:get",
                "-Dartifact=" + coordinate, "-Dtransitive=false"));
        }
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Maven did not place the artifact in the default local repository: " + jar);
        }
        resolved.put(coordinate, jar);
        return jar;
    }

    List<Path> resolveApiClasspath(String groupId, String artifactId, String version,
                                   String classifier, Path dependencyJar) throws IOException {
        String cacheKey = groupId + ":" + artifactId + ":" + version + ":" + classifier;
        List<Path> cached = apiClasspaths.get(cacheKey);
        if (cached != null) return cached;

        Path temporaryDirectory = Files.createTempDirectory("smartdeptest-api-classpath-");
        Path pom = temporaryDirectory.resolve("pom.xml");
        Path output = temporaryDirectory.resolve("classpath.txt");
        try {
            String classifierElement = classifier == null || classifier.isBlank() ? ""
                    : "<classifier>" + classifier + "</classifier>";
            String temporaryPom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                    + "<modelVersion>4.0.0</modelVersion><groupId>smartdeptest</groupId>"
                    + "<artifactId>api-analysis</artifactId><version>1</version><dependencies><dependency>"
                    + "<groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId>"
                    + "<version>" + version + "</version><type>jar</type>" + classifierElement
                    + "</dependency></dependencies></project>";
            Files.writeString(pom, temporaryPom);
            MavenCommandRunner.run(temporaryDirectory, List.of("mvn", "-q", "-f", pom.toString(),
                    "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:build-classpath",
                    "-DincludeScope=compile", "-Dmdep.outputFile=" + output));

            List<Path> entries = new java.util.ArrayList<>();
            entries.add(dependencyJar.toAbsolutePath().normalize());
            if (Files.isRegularFile(output)) {
                String classpath = Files.readString(output).trim();
                if (!classpath.isBlank()) {
                    for (String entry : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                        Path path = Path.of(entry);
                        if (Files.exists(path)) entries.add(path.toAbsolutePath().normalize());
                    }
                }
            }
            List<Path> result = entries.stream().distinct().toList();
            apiClasspaths.put(cacheKey, result);
            return result;
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(pom);
            Files.deleteIfExists(temporaryDirectory);
        }
    }

    Path expectedJarPath(String groupId, String artifactId, String version, String classifier) {
        String classifierSuffix = classifier.isBlank() ? "" : "-" + classifier;
        return localRepository.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version)
                .resolve(artifactId + "-" + version + classifierSuffix + ".jar");
    }

    private static boolean validCoordinatePart(String value) {
        return value != null && value.matches("[A-Za-z0-9_.+-]+")
                && !value.contains("${") && !value.isBlank();
    }
}