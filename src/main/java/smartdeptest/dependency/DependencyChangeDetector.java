package smartdeptest.dependency;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DependencyChangeDetector {
    private final PomParser pomParser = new PomParser();
    private final DependencyComparator comparator = new DependencyComparator();

    public DependencyChangeResult detect(Path projectDirectory) throws Exception {
        GitRepositoryAnalyzer git = new GitRepositoryAnalyzer(projectDirectory);
        System.out.println("[1/5] Verifying Git repository...");
        git.verifyRepository();
        System.out.println("[2/5] Discovering Maven POM files...");
        List<String> pomFiles = git.discoverPomFiles();
        if (pomFiles.isEmpty()) throw new IOException("No pom.xml files were found in the project.");
        System.out.println("      Found " + pomFiles.size() + " POM file(s).");
        System.out.println("[3/5] Finding POM-related commits in Git history...");
        List<GitRepositoryAnalyzer.CommitCandidate> commits = git.historyCandidates(pomFiles);
        System.out.println("      Found " + commits.size() + " POM-related commit candidate(s).");
        System.out.println("[4/5] Inspecting commits for dependency changes...");
        int processedCandidates = 0;
        List<String> diagnostics = new ArrayList<>();
        for (GitRepositoryAnalyzer.CommitCandidate candidate : commits) {
            processedCandidates++;
            if (processedCandidates == 1 || processedCandidates % 25 == 0) {
                System.out.println("      Processed " + processedCandidates + "/" + commits.size()
                        + " POM-related commit candidate(s)...");
            }
            String commit = candidate.commitId();
            String parent = candidate.firstParentId();
            if (parent == null) continue;
            List<DependencyChange> changes = new ArrayList<>();
            List<String> changedPoms = candidate.changedPomFiles();
            for (String pomPath : changedPoms) {
                try {
                    String oldPom = git.readPom(parent, pomPath);
                    String newPom = git.readPom(commit, pomPath);
                    Map<String, Dependency> oldDependencies = pomParser.parse(oldPom, pomPath);
                    Map<String, Dependency> newDependencies = pomParser.parse(newPom, pomPath);
                    changes.addAll(comparator.compare(oldDependencies, newDependencies, pomPath, commit, parent));
                } catch (Exception exception) {
                    String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                    diagnostics.add("Could not analyze POM " + pomPath + " in commit " + commit + ": " + detail);
                }
            }
            if (!changes.isEmpty()) {
                System.out.println("[5/5] Dependency-changing commit found.");
                return new DependencyChangeResult(projectDirectory.toAbsolutePath().normalize().toString(), commit,
                        parent, git.commitMessage(commit), changedPoms, changes, diagnostics);
            }
        }
        System.out.println("[5/5] No dependency-changing commit found.");
        if (!diagnostics.isEmpty()) {
            String diagnosticSummary = diagnostics.stream().limit(3)
                    .collect(java.util.stream.Collectors.joining(" | "));
            throw new IOException("No Maven dependency-changing commit was found in the available Git history. "
                    + diagnostics.size() + " POM analysis failure(s) occurred. " + diagnosticSummary);
        }
        if (git.isShallowRepository()) {
            throw new IOException("No Maven dependency-changing commit was found in the available Git history. "
                    + "The repository is shallow; fetch more history and try again.");
        }
        throw new IOException("No Maven dependency-changing commit was found in the available Git history.");
    }
}
