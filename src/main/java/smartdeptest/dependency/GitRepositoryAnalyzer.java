package smartdeptest.dependency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GitRepositoryAnalyzer {
    private static final long GIT_TIMEOUT_SECONDS =
            Long.getLong("smartdeptest.git.timeout.seconds", 120L);
    private final Path projectDirectory;

    public GitRepositoryAnalyzer(Path projectDirectory) { this.projectDirectory = projectDirectory.toAbsolutePath().normalize(); }

    public void verifyRepository() throws IOException {
        runGit("rev-parse", "--is-inside-work-tree");
    }

    public List<String> discoverPomFiles() throws IOException {
        try (var stream = Files.walk(projectDirectory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .filter(path -> !path.toString().contains("\\.git\\") && !path.toString().contains("/.git/"))
                    .filter(path -> !path.toString().contains("\\target\\") && !path.toString().contains("/target/"))
                    .filter(path -> !path.toString().contains("\\node_modules\\") && !path.toString().contains("/node_modules/"))
                    .map(projectDirectory::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    public List<String> historyCommits(List<String> pomFiles) throws IOException {
        List<String> arguments = new ArrayList<>();
        arguments.add("log");
        arguments.add("--first-parent");
        arguments.add("--no-renames");
        arguments.add("--format=%H");
        arguments.add("--");
        arguments.addAll(pomFiles);
        String output = runGit(arguments.toArray(String[]::new));
        return Arrays.stream(output.split("\\R")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    public List<CommitCandidate> historyCandidates(List<String> pomFiles) throws IOException {
        List<String> arguments = new ArrayList<>();
        arguments.add("log");
        arguments.add("--first-parent");
        arguments.add("--no-renames");
        arguments.add("--diff-merges=first-parent");
        arguments.add("--format=commit:%H%x09%P");
        arguments.add("--name-only");
        arguments.add("--");
        arguments.addAll(pomFiles);
        String output = runGit(arguments.toArray(String[]::new));

        List<CommitCandidate> candidates = new ArrayList<>();
        String commitId = null;
        String parentId = null;
        List<String> changedPomFiles = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.startsWith("commit:")) {
                if (commitId != null) {
                    candidates.add(new CommitCandidate(commitId, parentId, changedPomFiles));
                }
                String[] metadata = line.substring("commit:".length()).split("\\t", 2);
                commitId = metadata[0].trim();
                String[] parents = metadata.length < 2 ? new String[0] : metadata[1].trim().split("\\s+");
                parentId = parents.length == 0 || parents[0].isBlank() ? null : parents[0];
                changedPomFiles = new ArrayList<>();
            } else if (commitId != null && !line.isBlank()) {
                changedPomFiles.add(line.trim().replace('\\', '/'));
            }
        }
        if (commitId != null) {
            candidates.add(new CommitCandidate(commitId, parentId, changedPomFiles));
        }
        return candidates;
    }

    public String firstParent(String commit) throws IOException {
        String output = runGit("rev-list", "--parents", "-n", "1", commit);
        String[] values = output.trim().split("\\s+");
        if (values.length < 2) return null;
        return values[1];
    }

    public String commitMessage(String commit) throws IOException { return runGit("show", "-s", "--format=%s", commit).trim(); }

    public boolean isShallowRepository() throws IOException {
        return "true".equalsIgnoreCase(runGit("rev-parse", "--is-shallow-repository").trim());
    }

    public List<String> changedPomFiles(String parent, String commit, List<String> pomFiles) throws IOException {
        List<String> arguments = new ArrayList<>();
        arguments.add("diff");
        arguments.add("--name-only");
        arguments.add(parent);
        arguments.add(commit);
        arguments.add("--");
        arguments.addAll(pomFiles);
        String output = runGit(arguments.toArray(String[]::new));
        return Arrays.stream(output.split("\\R")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.replace('\\', '/')).distinct().sorted().toList();
    }

    public String readPom(String commit, String pomPath) throws IOException {
        ProcessResult result = runGitAllowFailure("cat-file", "blob", commit + ":" + pomPath);
        return result.exitCode == 0 ? result.output : "";
    }

    private String runGit(String... arguments) throws IOException {
        ProcessResult result = runGitAllowFailure(arguments);
        if (result.exitCode != 0) throw new IOException(result.output.isBlank() ? "Git command failed" : result.output.trim());
        return result.output;
    }

    private ProcessResult runGitAllowFailure(String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git"); command.addAll(List.of(arguments));
        Process process;
        try {
            process = new ProcessBuilder(command).directory(projectDirectory.toFile()).redirectErrorStream(true).start();
        } catch (IOException exception) {
            throw new IOException("Git is not installed or is not available in PATH.", exception);
        }
        try {
            java.util.concurrent.CompletableFuture<byte[]> output =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return process.getInputStream().readAllBytes();
                        } catch (IOException exception) {
                            throw new java.util.concurrent.CompletionException(exception);
                        }
                    });
            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Git command timed out after "
                        + GIT_TIMEOUT_SECONDS + " seconds: " + String.join(" ", command));
            }
            return new ProcessResult(process.exitValue(),
                    new String(output.join(), StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command was interrupted.", exception);
        }
    }

    public record CommitCandidate(String commitId, String firstParentId, List<String> changedPomFiles) {
        public CommitCandidate {
            changedPomFiles = changedPomFiles.stream().distinct().sorted().toList();
        }
    }

    private record ProcessResult(int exitCode, String output) {}
}
