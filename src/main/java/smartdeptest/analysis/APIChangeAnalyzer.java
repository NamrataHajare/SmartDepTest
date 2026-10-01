package smartdeptest.analysis;

import smartdeptest.dependency.DependencyChange;
import smartdeptest.dependency.DependencyChangeResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class APIChangeAnalyzer {
    private final MavenArtifactResolver artifactResolver;
    private final JApiCmpApiComparator apiComparator = new JApiCmpApiComparator();

    public APIChangeAnalyzer() {
        this(new MavenArtifactResolver());
    }

    APIChangeAnalyzer(MavenArtifactResolver artifactResolver) {
        this.artifactResolver = artifactResolver;
    }

    public APIChangeResult analyze(DependencyChangeResult dependencyChanges) {
        Path projectDirectory = Path.of(dependencyChanges.getProjectPath()).toAbsolutePath().normalize();
        Map<String, DependencyChange> uniqueChanges = new LinkedHashMap<>();
        for (DependencyChange change : dependencyChanges.getChanges()) {
            String key = change.getGroupId() + ":" + change.getArtifactId() + ":"
                    + change.getOldVersion() + ":" + change.getNewVersion() + ":"
                + change.getOldClassifier() + ":" + change.getNewClassifier() + ":"
                + change.getOldType() + ":" + change.getNewType() + ":" + change.getPomPath();
            uniqueChanges.merge(key, change, (existing, candidate) ->
                existing.isDependencyManagement() && !candidate.isDependencyManagement()
                    ? candidate : existing);
        }

        List<DependencyApiResult> results = new ArrayList<>();
        for (DependencyChange change : uniqueChanges.values()) {
            results.add(analyzeDependency(change, projectDirectory));
        }
        return new APIChangeResult(dependencyChanges.getProjectPath(), results);
    }

    private DependencyApiResult analyzeDependency(DependencyChange change, Path projectDirectory) {
        String groupId = change.getGroupId();
        String artifactId = change.getArtifactId();
        String oldVersion = change.getOldVersion();
        String newVersion = change.getNewVersion();
        if ((!change.getOldType().isBlank() && !"jar".equals(change.getOldType()))
            || (!change.getNewType().isBlank() && !"jar".equals(change.getNewType()))) {
                return unavailable(change, null, null,
                "API analysis currently supports JAR dependencies, not Maven type "
                    + change.getNewType() + ".");
        }
        if (oldVersion.isBlank() || newVersion.isBlank() || oldVersion.equals("NOT_SPECIFIED")
                || newVersion.equals("NOT_SPECIFIED")) {
                return unavailable(change, null, null,
                    "Both old and new dependency versions are required for API comparison.");
        }
        if (oldVersion.equals(newVersion)) {
                return new DependencyApiResult(groupId, artifactId, oldVersion, newVersion,
                    change.getOldClassifier(), change.getNewClassifier(), change.getOldScope(), change.getNewScope(),
                    change.getOldType(), change.getNewType(), change.isDependencyManagement(),
                    change.getPomPath(), "", "", "", "",
                DependencyApiResult.Status.ANALYZED,
                "Dependency version did not change; JAR comparison was skipped.", List.of());
        }

            MavenArtifactResolver.ResolvedArtifact oldArtifact = null;
            MavenArtifactResolver.ResolvedArtifact newArtifact = null;
        try {
                oldArtifact = artifactResolver.resolveJar(projectDirectory, change.getPomPath(), groupId,
                    artifactId, oldVersion, change.getOldClassifier());
                newArtifact = artifactResolver.resolveJar(projectDirectory, change.getPomPath(), groupId,
                    artifactId, newVersion, change.getNewClassifier());
                    List<Path> oldClasspath = artifactResolver.resolveApiClasspath(projectDirectory, change.getPomPath(),
                        groupId, artifactId, oldVersion, change.getOldClassifier(), oldArtifact);
                    List<Path> newClasspath = artifactResolver.resolveApiClasspath(projectDirectory, change.getPomPath(),
                        groupId, artifactId, newVersion, change.getNewClassifier(), newArtifact);
                    Path newArtifactSuffix = MavenArtifactResolver.artifactPathSuffix(groupId, artifactId,
                        newVersion, change.getNewClassifier());
                    Path oldArtifactSuffix = MavenArtifactResolver.artifactPathSuffix(groupId, artifactId,
                        oldVersion, change.getOldClassifier());
                    oldClasspath = oldClasspath.stream().filter(path -> !path.toAbsolutePath().normalize()
                        .endsWith(newArtifactSuffix)).toList();
                    newClasspath = newClasspath.stream().filter(path -> !path.toAbsolutePath().normalize()
                        .endsWith(oldArtifactSuffix)).toList();
            long comparisonStarted = System.nanoTime();
            List<ApiChange> changes;
            String comparisonMessage = "";
            try {
                changes = apiComparator.compare(oldArtifact.jar(), newArtifact.jar(),
                        oldClasspath, newClasspath, false);
            } catch (RuntimeException strictFailure) {
                String detail = strictFailure.getMessage();
                if (detail == null || !detail.contains("Class not found:")) throw strictFailure;
                changes = apiComparator.compare(oldArtifact.jar(), newArtifact.jar(),
                        oldClasspath, newClasspath, true);
                comparisonMessage = "JApiCmp ignored unresolved transitive class references: " + detail;
            }
            System.out.printf("JApiCmp analysis for %s:%s completed in %d ms.%n", groupId, artifactId,
                    (System.nanoTime() - comparisonStarted) / 1_000_000);
            return new DependencyApiResult(groupId, artifactId, oldVersion, newVersion,
                    change.getOldClassifier(), change.getNewClassifier(), change.getOldScope(), change.getNewScope(),
                    change.getOldType(), change.getNewType(), change.isDependencyManagement(), change.getPomPath(),
                    oldArtifact.jar().toString(), newArtifact.jar().toString(),
                    oldArtifact.source(), newArtifact.source(),
                    DependencyApiResult.Status.ANALYZED, comparisonMessage, changes);
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                return unavailable(change, oldArtifact, newArtifact, message);
        }
    }

    private static DependencyApiResult unavailable(DependencyChange change,
                                                   MavenArtifactResolver.ResolvedArtifact oldArtifact,
                                                   MavenArtifactResolver.ResolvedArtifact newArtifact,
                                                   String message) {
        return new DependencyApiResult(change.getGroupId(), change.getArtifactId(),
                change.getOldVersion(), change.getNewVersion(), change.getOldClassifier(), change.getNewClassifier(),
            change.getOldScope(), change.getNewScope(), change.getOldType(), change.getNewType(),
            change.isDependencyManagement(),
                change.getPomPath(),
                oldArtifact == null ? "" : oldArtifact.jar().toString(),
                newArtifact == null ? "" : newArtifact.jar().toString(),
                oldArtifact == null ? "" : oldArtifact.source(),
                newArtifact == null ? "" : newArtifact.source(),
                DependencyApiResult.Status.UNAVAILABLE, message, List.of());
    }
}