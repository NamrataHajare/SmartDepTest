package smartdeptest.dependency;

import java.util.List;

public final class DependencyChangeResult {
    private final String projectPath;
    private final String commitId;
    private final String previousCommitId;
    private final String commitMessage;
    private final List<String> changedPomFiles;
    private final List<DependencyChange> changes;
    private final List<String> diagnostics;

    public DependencyChangeResult(String projectPath, String commitId, String previousCommitId,
                                   String commitMessage, List<String> changedPomFiles,
                                   List<DependencyChange> changes) {
        this(projectPath, commitId, previousCommitId, commitMessage, changedPomFiles, changes, List.of());
    }

    public DependencyChangeResult(String projectPath, String commitId, String previousCommitId,
                                   String commitMessage, List<String> changedPomFiles,
                                   List<DependencyChange> changes, List<String> diagnostics) {
        this.projectPath = projectPath;
        this.commitId = commitId;
        this.previousCommitId = previousCommitId;
        this.commitMessage = commitMessage;
        this.changedPomFiles = List.copyOf(changedPomFiles);
        this.changes = List.copyOf(changes);
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public String getProjectPath() { return projectPath; }
    public String getCommitId() { return commitId; }
    public String getPreviousCommitId() { return previousCommitId; }
    public String getCommitMessage() { return commitMessage; }
    public List<String> getChangedPomFiles() { return changedPomFiles; }
    public List<DependencyChange> getChanges() { return changes; }
    public List<String> getDiagnostics() { return diagnostics; }
}
