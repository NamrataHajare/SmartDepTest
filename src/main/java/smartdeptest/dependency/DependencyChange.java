package smartdeptest.dependency;

public final class DependencyChange {
    public enum Type { ADDED, REMOVED, UPDATED, SCOPE_CHANGED, TYPE_CHANGED, CLASSIFIER_CHANGED, OPTIONAL_CHANGED }

    private final Type changeType;
    private final String groupId;
    private final String artifactId;
    private final String oldVersion;
    private final String newVersion;
    private final String oldScope;
    private final String newScope;
    private final String oldType;
    private final String newType;
    private final String pomPath;
    private final String commitId;
    private final String previousCommitId;
    private final boolean dependencyManagement;

    public DependencyChange(Type changeType, Dependency oldDependency, Dependency newDependency,
                            String pomPath, String commitId, String previousCommitId) {
        this.changeType = changeType;
        Dependency reference = newDependency != null ? newDependency : oldDependency;
        this.groupId = reference.getGroupId();
        this.artifactId = reference.getArtifactId();
        this.oldVersion = oldDependency == null ? "" : oldDependency.getVersion();
        this.newVersion = newDependency == null ? "" : newDependency.getVersion();
        this.oldScope = oldDependency == null ? "" : oldDependency.getScope();
        this.newScope = newDependency == null ? "" : newDependency.getScope();
        this.oldType = oldDependency == null ? "" : oldDependency.getType();
        this.newType = newDependency == null ? "" : newDependency.getType();
        this.pomPath = pomPath;
        this.commitId = commitId;
        this.previousCommitId = previousCommitId;
        this.dependencyManagement = reference.isDependencyManagement();
    }

    public Type getChangeType() { return changeType; }
    public String getGroupId() { return groupId; }
    public String getArtifactId() { return artifactId; }
    public String getDependencyKey() { return groupId + ":" + artifactId; }
    public String getOldVersion() { return oldVersion; }
    public String getNewVersion() { return newVersion; }
    public String getOldScope() { return oldScope; }
    public String getNewScope() { return newScope; }
    public String getOldType() { return oldType; }
    public String getNewType() { return newType; }
    public String getPomPath() { return pomPath; }
    public String getCommitId() { return commitId; }
    public String getPreviousCommitId() { return previousCommitId; }
    public boolean isDependencyManagement() { return dependencyManagement; }
}
