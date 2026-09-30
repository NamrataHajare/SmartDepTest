package smartdeptest.analysis;

import java.util.List;

public record DependencyApiResult(String groupId, String artifactId,
                                  String oldVersion, String newVersion,
                                  String oldClassifier, String newClassifier,
                                  Status status, String message,
                                  List<ApiChange> changes) {
    public enum Status { ANALYZED, UNAVAILABLE }

    public DependencyApiResult {
        changes = List.copyOf(changes);
        message = message == null ? "" : message;
        oldClassifier = oldClassifier == null ? "" : oldClassifier;
        newClassifier = newClassifier == null ? "" : newClassifier;
    }

    public String dependencyKey() {
        return groupId + ":" + artifactId;
    }
}