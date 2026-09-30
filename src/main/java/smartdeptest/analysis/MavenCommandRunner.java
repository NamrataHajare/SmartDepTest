package smartdeptest.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class MavenCommandRunner {
    private static final long TIMEOUT_SECONDS = 300;

    private MavenCommandRunner() {}

    static String run(Path workingDirectory, List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            String commandLine = String.join(" ", arguments.stream()
                    .map(MavenCommandRunner::quoteForCommandPrompt).toList());
            command.addAll(List.of("cmd.exe", "/d", "/s", "/c", commandLine));
        } else {
            command.addAll(arguments);
        }

        Process process;
        try {
            process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                    .redirectErrorStream(true).start();
        } catch (IOException exception) {
            throw new IOException("Maven is not installed or is not available in PATH.", exception);
        }
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        });
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Maven command timed out after " + TIMEOUT_SECONDS + " seconds.");
            }
            String text = new String(output.join(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException(text.isBlank() ? "Maven command failed." : text.trim());
            }
            return text;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Maven command was interrupted.", exception);
        }
    }

    private static String quoteForCommandPrompt(String argument) {
        if (argument.chars().noneMatch(Character::isWhitespace) && !argument.contains("&")) {
            return argument;
        }
        return "\"" + argument.replace("\"", "\"\"") + "\"";
    }
}