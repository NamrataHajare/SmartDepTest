
package smartdeptest.analysis;

import smartdeptest.dependency.DependencyChange;
import smartdeptest.dependency.DependencyChangeResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class APIChangeAnalyzer {
    private final MavenArtifactResolver artifactResolver;
    private final AsmApiComparator apiComparator = new AsmApiComparator();

    public APIChangeAnalyzer() {
        this(new MavenArtifactResolver());
    }

    APIChangeAnalyzer(MavenArtifactResolver artifactResolver) {
        this.artifactResolver = artifactResolver;
    }

    public APIChangeResult analyze(DependencyChangeResult dependencyChanges) {
        Path projectDirectory = Path.of(dependencyChanges.getProjectPath())
                .toAbsolutePath()
                .normalize();

        Map<String, DependencyChange> uniqueChanges = new LinkedHashMap<>();

        for (DependencyChange change : dependencyChanges.getChanges()) {
            String key = change.getGroupId() + ":" + change.getArtifactId() + ":"
                    + change.getOldVersion() + ":" + change.getNewVersion() + ":"
                    + change.getOldClassifier() + ":" + change.getNewClassifier() + ":"
                    + change.getOldType() + ":" + change.getNewType() + ":"
                    + change.getPomPath();

            uniqueChanges.merge(key, change,
                    (existing, candidate) -> existing.isDependencyManagement()
                            && !candidate.isDependencyManagement()
                                    ? candidate
                                    : existing);
        }

        List<DependencyApiResult> results = new ArrayList<>();
        for (DependencyChange change : uniqueChanges.values()) {
            try {
                results.add(analyzeDependency(change, projectDirectory));
            } catch (RuntimeException exception) {
                results.add(unavailable(
                        change, null, null, messageOf(exception)));
            }
        }

        return new APIChangeResult(
                dependencyChanges.getProjectPath(), results);
    }

    private DependencyApiResult analyzeDependency(
            DependencyChange change,
            Path projectDirectory) {

        String oldVersion = normalizeVersion(change.getOldVersion());
        String newVersion = normalizeVersion(change.getNewVersion());

        boolean hasOldVersion = hasVersion(oldVersion);
        boolean hasNewVersion = hasVersion(newVersion);

        if ((!change.getOldType().isBlank()
                && !"jar".equals(change.getOldType()))
                || (!change.getNewType().isBlank()
                        && !"jar".equals(change.getNewType()))) {
            return unavailable(change, null, null,
                    "API analysis currently supports JAR dependencies only.");
        }

        if (!hasOldVersion && !hasNewVersion) {
            return unavailable(change, null, null,
                    "Neither an old nor a new dependency version is available.");
        }

        if (hasOldVersion && hasNewVersion
                && oldVersion.equals(newVersion)
                && normalizeClassifier(change.getOldClassifier())
                        .equals(normalizeClassifier(change.getNewClassifier()))) {
            return new DependencyApiResult(
                    change.getGroupId(), change.getArtifactId(),
                    oldVersion, newVersion,
                    change.getOldClassifier(), change.getNewClassifier(),
                    change.getOldScope(), change.getNewScope(),
                    change.getOldType(), change.getNewType(),
                    change.isDependencyManagement(), change.getPomPath(),
                    "", "", "", "",
                    DependencyApiResult.Status.ANALYZED,
                    "Dependency version did not change; JAR comparison was skipped.",
                    List.of());
        }

        MavenArtifactResolver.ResolvedArtifact oldArtifact = null;
        MavenArtifactResolver.ResolvedArtifact newArtifact = null;
        Path emptyJar = null;

        try {
            if (hasOldVersion) {
                oldArtifact = artifactResolver.resolveJar(
                        projectDirectory, change.getPomPath(),
                        change.getGroupId(), change.getArtifactId(),
                        oldVersion, change.getOldClassifier());
            }

            if (hasNewVersion) {
                newArtifact = artifactResolver.resolveJar(
                        projectDirectory, change.getPomPath(),
                        change.getGroupId(), change.getArtifactId(),
                        newVersion, change.getNewClassifier());
            }

            emptyJar = createEmptyJar();

            Path oldJar = oldArtifact == null
                    ? emptyJar
                    : oldArtifact.jar();

            Path newJar = newArtifact == null
                    ? emptyJar
                    : newArtifact.jar();

            List<ApiChange> changes = apiComparator.compare(oldJar, newJar);
            String comparisonMessage = "";

            if (!hasOldVersion) {
                comparisonMessage = appendMessage(
                        comparisonMessage,
                        "Added dependency: API compared against an empty baseline.");
            } else if (!hasNewVersion) {
                comparisonMessage = appendMessage(
                        comparisonMessage,
                        "Removed dependency: API compared against an empty target.");
            }

            return new DependencyApiResult(
                    change.getGroupId(), change.getArtifactId(),
                    oldVersion, newVersion,
                    change.getOldClassifier(), change.getNewClassifier(),
                    change.getOldScope(), change.getNewScope(),
                    change.getOldType(), change.getNewType(),
                    change.isDependencyManagement(), change.getPomPath(),
                    oldArtifact == null ? "" : oldArtifact.jar().toString(),
                    newArtifact == null ? "" : newArtifact.jar().toString(),
                    oldArtifact == null ? "" : oldArtifact.source(),
                    newArtifact == null ? "" : newArtifact.source(),
                    DependencyApiResult.Status.ANALYZED,
                    comparisonMessage, changes);

        } catch (IOException | RuntimeException exception) {
            System.err.println();
            System.err.println("========== FULL API ANALYSIS ERROR ==========");
            System.err.println("Dependency: " + change.getGroupId() + ":"
                    + change.getArtifactId() + " "
                    + oldVersion + " -> " + newVersion);
            exception.printStackTrace(System.err);
            System.err.println("========== END API ANALYSIS ERROR ==========");
            return unavailable(change, oldArtifact, newArtifact,
                    messageOf(exception));
        } finally {
            if (emptyJar != null) {
                try {
                    Files.deleteIfExists(emptyJar);
                } catch (IOException exception) {
                    System.err.printf(
                            "[WARN] Could not remove temporary comparison JAR: %s%n",
                            emptyJar);
                }
            }
        }
    }

    private static Path createEmptyJar() throws IOException {
        Path jar = Files.createTempFile(
                "smartdeptest-empty-api-", ".jar");

        try {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue(
                    "Manifest-Version", "1.0");

            try (JarOutputStream ignored = new JarOutputStream(
                    Files.newOutputStream(jar), manifest)) {
            }

            return jar;
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(jar);
            throw exception;
        }
    }

    private static String normalizeVersion(String version) {
        return version == null ? "" : version.trim();
    }

    private static String normalizeClassifier(String classifier) {
        return classifier == null ? "" : classifier.trim();
    }

    private static boolean hasVersion(String version) {
        return version != null && !version.isBlank()
                && !"NOT_SPECIFIED".equals(version);
    }

    private static String appendMessage(String existing, String additional) {
        return existing == null || existing.isBlank()
                ? additional
                : existing + " " + additional;
    }

    private static String messageOf(Throwable exception) {
        String message = exception.getMessage();

        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static DependencyApiResult unavailable(
            DependencyChange change,
            MavenArtifactResolver.ResolvedArtifact oldArtifact,
            MavenArtifactResolver.ResolvedArtifact newArtifact,
            String message) {

        return new DependencyApiResult(
                change.getGroupId(), change.getArtifactId(),
                change.getOldVersion(), change.getNewVersion(),
                change.getOldClassifier(), change.getNewClassifier(),
                change.getOldScope(), change.getNewScope(),
                change.getOldType(), change.getNewType(),
                change.isDependencyManagement(), change.getPomPath(),
                oldArtifact == null ? "" : oldArtifact.jar().toString(),
                newArtifact == null ? "" : newArtifact.jar().toString(),
                oldArtifact == null ? "" : oldArtifact.source(),
                newArtifact == null ? "" : newArtifact.source(),
                DependencyApiResult.Status.UNAVAILABLE,
                message, List.of());
    }
}
