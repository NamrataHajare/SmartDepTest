package smartdeptest.dependency;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

public final class DependencyComparator {
    public List<DependencyChange> compare(Map<String, Dependency> oldDependencies,
                                          Map<String, Dependency> newDependencies,
                                          String pomPath, String commitId, String previousCommitId) {
        List<DependencyChange> changes = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(oldDependencies.keySet());
        keys.addAll(newDependencies.keySet());
        for (String key : keys) {
            Dependency oldDependency = oldDependencies.get(key);
            Dependency newDependency = newDependencies.get(key);
            if (oldDependency == null) {
                changes.add(new DependencyChange(DependencyChange.Type.ADDED, null, newDependency,
                        pomPath, commitId, previousCommitId));
            } else if (newDependency == null) {
                changes.add(new DependencyChange(DependencyChange.Type.REMOVED, oldDependency, null,
                        pomPath, commitId, previousCommitId));
            } else {
                addMetadataChanges(changes, oldDependency, newDependency, pomPath, commitId, previousCommitId);
            }
        }
        return changes;
    }

    private void addMetadataChanges(List<DependencyChange> changes, Dependency oldDependency,
                                    Dependency newDependency, String pomPath, String commitId,
                                    String previousCommitId) {
        if (!oldDependency.getVersion().equals(newDependency.getVersion())) {
            changes.add(new DependencyChange(DependencyChange.Type.UPDATED, oldDependency, newDependency,
                    pomPath, commitId, previousCommitId));
        }
        if (!oldDependency.getScope().equals(newDependency.getScope())) {
            changes.add(new DependencyChange(DependencyChange.Type.SCOPE_CHANGED, oldDependency, newDependency,
                    pomPath, commitId, previousCommitId));
        }
        if (!oldDependency.getType().equals(newDependency.getType())) {
            changes.add(new DependencyChange(DependencyChange.Type.TYPE_CHANGED, oldDependency, newDependency,
                    pomPath, commitId, previousCommitId));
        }
        if (!oldDependency.getClassifier().equals(newDependency.getClassifier())) {
            changes.add(new DependencyChange(DependencyChange.Type.CLASSIFIER_CHANGED, oldDependency, newDependency,
                    pomPath, commitId, previousCommitId));
        }
        if (oldDependency.isOptional() != newDependency.isOptional()) {
            changes.add(new DependencyChange(DependencyChange.Type.OPTIONAL_CHANGED, oldDependency, newDependency,
                    pomPath, commitId, previousCommitId));
        }
    }
}
