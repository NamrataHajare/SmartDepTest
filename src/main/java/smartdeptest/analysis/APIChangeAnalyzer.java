package smartdeptest.analysis;

import smartdeptest.dependency.DependencyChange;
import smartdeptest.dependency.DependencyChangeResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class APIChangeAnalyzer {
    private final MavenArtifactResolver artifactResolver;
    private final ApiSurfaceReader surfaceReader = new ApiSurfaceReader();
    private final Map<Path, Map<String, ApiSurfaceReader.Member>> surfaceCache = new HashMap<>();

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
                    + change.getOldClassifier() + ":" + change.getNewClassifier() + ":" + change.getPomPath();
            uniqueChanges.putIfAbsent(key, change);
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
                    change.getOldClassifier(), change.getNewClassifier(), change.getPomPath(), "", "", "", "",
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
                Map<String, ApiSurfaceReader.Member> oldApi = readSurface(oldArtifact.jar(), oldClasspath);
                Map<String, ApiSurfaceReader.Member> newApi = readSurface(newArtifact.jar(), newClasspath);
            List<ApiChange> changes = compare(oldApi, newApi);
            return new DependencyApiResult(groupId, artifactId, oldVersion, newVersion,
                    change.getOldClassifier(), change.getNewClassifier(), change.getPomPath(),
                    oldArtifact.jar().toString(), newArtifact.jar().toString(),
                    oldArtifact.source(), newArtifact.source(),
                    DependencyApiResult.Status.ANALYZED, "", changes);
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                return unavailable(change, oldArtifact, newArtifact, message);
        }
    }

    private Map<String, ApiSurfaceReader.Member> readSurface(Path jar, List<Path> classpath) throws IOException {
        Path normalized = jar.toAbsolutePath().normalize();
        Map<String, ApiSurfaceReader.Member> cached = surfaceCache.get(normalized);
        if (cached != null) return cached;
        Map<String, ApiSurfaceReader.Member> surface = surfaceReader.read(normalized, classpath);
        surfaceCache.put(normalized, surface);
        return surface;
    }

    private static List<ApiChange> compare(Map<String, ApiSurfaceReader.Member> oldApi,
                                           Map<String, ApiSurfaceReader.Member> newApi) {
        Map<String, ApiSurfaceReader.Member> removed = new LinkedHashMap<>();
        Map<String, ApiSurfaceReader.Member> added = new LinkedHashMap<>();
        List<ApiChange> changes = new ArrayList<>();

        oldApi.forEach((key, oldMember) -> {
            ApiSurfaceReader.Member newMember = newApi.get(key);
            if (newMember == null) removed.put(key, oldMember);
            else if (!oldMember.signature().equals(newMember.signature())) {
                changes.add(toChange(ApiChange.Kind.CLASS_MODIFIED.equals(oldMember.kind())
                                ? ApiChange.Kind.CLASS_MODIFIED : modifiedKind(oldMember.kind()),
                        oldMember, newMember, true));
            }
        });
        newApi.forEach((key, newMember) -> {
            if (!oldApi.containsKey(key)) added.put(key, newMember);
        });

        pairChangedMethodSignatures(removed, added, changes);
        removed.values().forEach(member -> changes.add(toChange(removedKind(member.kind()), member, null, true)));
        added.values().forEach(member -> changes.add(toChange(addedKind(member.kind()), null, member,
            member.kind() == ApiChange.Kind.METHOD_MODIFIED && member.potentiallyIncompatible())));
        return List.copyOf(changes);
    }

    private static void pairChangedMethodSignatures(Map<String, ApiSurfaceReader.Member> removed,
                                                    Map<String, ApiSurfaceReader.Member> added,
                                                    List<ApiChange> changes) {
        Map<String, List<String>> removedByName = groupMethods(removed);
        Map<String, List<String>> addedByName = groupMethods(added);
        for (Map.Entry<String, List<String>> entry : removedByName.entrySet()) {
            List<String> oldKeys = entry.getValue();
            List<String> newKeys = addedByName.get(entry.getKey());
            if (oldKeys.size() == 1 && newKeys != null && newKeys.size() == 1) {
                ApiSurfaceReader.Member oldMember = removed.remove(oldKeys.get(0));
                ApiSurfaceReader.Member newMember = added.remove(newKeys.get(0));
                changes.add(toChange(ApiChange.Kind.METHOD_MODIFIED, oldMember, newMember, true));
            }
        }
    }

    private static Map<String, List<String>> groupMethods(Map<String, ApiSurfaceReader.Member> members) {
        Map<String, List<String>> groups = new HashMap<>();
        members.forEach((key, member) -> {
            if (member.kind() == ApiChange.Kind.METHOD_MODIFIED) {
                groups.computeIfAbsent(member.className() + "#" + member.memberName(), ignored -> new ArrayList<>())
                        .add(key);
            }
        });
        return groups;
    }

    private static ApiChange toChange(ApiChange.Kind kind, ApiSurfaceReader.Member oldMember,
                                     ApiSurfaceReader.Member newMember, boolean incompatible) {
        ApiSurfaceReader.Member reference = newMember == null ? oldMember : newMember;
        return new ApiChange(kind, reference.className(), reference.memberName(),
                oldMember == null ? "" : oldMember.signature(),
                newMember == null ? "" : newMember.signature(),
                oldMember == null ? List.of() : oldMember.parameterTypes(), incompatible);
    }

    private static ApiChange.Kind addedKind(ApiChange.Kind base) {
        return switch (base) {
            case CLASS_MODIFIED -> ApiChange.Kind.CLASS_ADDED;
            case METHOD_MODIFIED -> ApiChange.Kind.METHOD_ADDED;
            case FIELD_MODIFIED -> ApiChange.Kind.FIELD_ADDED;
            default -> throw new IllegalArgumentException("Unsupported API member kind: " + base);
        };
    }

    private static ApiChange.Kind removedKind(ApiChange.Kind base) {
        return switch (base) {
            case CLASS_MODIFIED -> ApiChange.Kind.CLASS_REMOVED;
            case METHOD_MODIFIED -> ApiChange.Kind.METHOD_REMOVED;
            case FIELD_MODIFIED -> ApiChange.Kind.FIELD_REMOVED;
            default -> throw new IllegalArgumentException("Unsupported API member kind: " + base);
        };
    }

    private static ApiChange.Kind modifiedKind(ApiChange.Kind base) {
        return base;
    }

    private static DependencyApiResult unavailable(DependencyChange change,
                                                   MavenArtifactResolver.ResolvedArtifact oldArtifact,
                                                   MavenArtifactResolver.ResolvedArtifact newArtifact,
                                                   String message) {
        return new DependencyApiResult(change.getGroupId(), change.getArtifactId(),
                change.getOldVersion(), change.getNewVersion(), change.getOldClassifier(), change.getNewClassifier(),
                change.getPomPath(),
                oldArtifact == null ? "" : oldArtifact.jar().toString(),
                newArtifact == null ? "" : newArtifact.jar().toString(),
                oldArtifact == null ? "" : oldArtifact.source(),
                newArtifact == null ? "" : newArtifact.source(),
                DependencyApiResult.Status.UNAVAILABLE, message, List.of());
    }
}