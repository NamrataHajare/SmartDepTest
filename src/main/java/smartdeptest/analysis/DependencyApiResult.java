package smartdeptest.analysis;

import java.util.List;

public record DependencyApiResult(String groupId, String artifactId,
        String oldVersion, String newVersion,
        String oldClassifier, String newClassifier,
        String oldScope, String newScope, String oldType, String newType,
        boolean dependencyManagement,
        String pomPath, String oldArtifactPath, String newArtifactPath,
        String oldResolutionSource, String newResolutionSource,
        Status status, String message,
        List<ApiChange> changes) {
    public enum Status {
        ANALYZED, UNAVAILABLE
    }

    public DependencyApiResult {
        changes = List.copyOf(changes);
        message = message == null ? "" : message;
        oldClassifier = oldClassifier == null ? "" : oldClassifier;
        newClassifier = newClassifier == null ? "" : newClassifier;
        oldScope = oldScope == null ? "" : oldScope;
        newScope = newScope == null ? "" : newScope;
        oldType = oldType == null ? "" : oldType;
        newType = newType == null ? "" : newType;
        pomPath = pomPath == null ? "" : pomPath;
        oldArtifactPath = oldArtifactPath == null ? "" : oldArtifactPath;
        newArtifactPath = newArtifactPath == null ? "" : newArtifactPath;
        oldResolutionSource = oldResolutionSource == null ? "" : oldResolutionSource;
        newResolutionSource = newResolutionSource == null ? "" : newResolutionSource;
    }

    /** Retained for downstream compatibility; ASM comparisons are complete or unavailable. */
    public boolean comparisonIncomplete() {
        return false;
    }

    public String dependencyKey() {
        return groupId + ":" + artifactId;
    }
}