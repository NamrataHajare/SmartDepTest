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
        arguments.add("--max-count=10");
        arguments.add("--format=%H");
        arguments.add("--");
        arguments.addAll(pomFiles);
        String output = runGit(arguments.toArray(String[]::new));
        return Arrays.stream(output.split("\\R")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    public String firstParent(String commit) throws IOException {
        String output = runGit("rev-list", "--parents", "-n", "1", commit);
        String[] values = output.trim().split("\\s+");
        if (values.length < 2) return null;
        return values[1];
    }

    public String commitMessage(String commit) throws IOException { return runGit("show", "-s", "--format=%s", commit).trim(); }

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

    private record ProcessResult(int exitCode, String output) {}
}
