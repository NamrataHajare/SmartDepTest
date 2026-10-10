
package smartdeptest.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class HistoricalProjectSnapshots {
    private HistoricalProjectSnapshots() {
    }

    static Path create(Path projectRoot, String commitId) throws IOException {
        if (commitId == null || commitId.isBlank()) {
            throw new IOException("A Git commit ID is required to create a historical project snapshot.");
        }

        Path root = projectRoot.toAbsolutePath().normalize();
        Path snapshot = Files.createTempDirectory("smartdeptest-history-");
        Path archive = Files.createTempFile("smartdeptest-history-", ".zip");

        try {
            Process process = new ProcessBuilder(
                    "git", "-C", root.toString(),
                    "archive", "--format=zip",
                    "--output=" + archive,
                    commitId)
                    .redirectErrorStream(true)
                    .start();

            boolean completed;
            try {
                completed = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("Interrupted while creating a historical Git snapshot.", exception);
            }

            if (!completed) {
                process.destroyForcibly();
                throw new IOException("Timed out creating a historical snapshot for commit " + commitId + ".");
            }

            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);

            if (process.exitValue() != 0) {
                throw new IOException("Could not export Git commit " + commitId + ": " + output.trim());
            }

            try (InputStream fileInput = Files.newInputStream(archive);
                 ZipInputStream zipInput = new ZipInputStream(fileInput)) {
                ZipEntry entry;

                while ((entry = zipInput.getNextEntry()) != null) {
                    Path destination = snapshot.resolve(entry.getName()).normalize();

                    if (!destination.startsWith(snapshot)) {
                        throw new IOException("Unsafe path in Git archive: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                    } else {
                        Path parent = destination.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.copy(zipInput, destination);
                    }

                    zipInput.closeEntry();
                }
            }

            return snapshot;
        } catch (IOException | RuntimeException exception) {
            delete(snapshot);
            throw exception;
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    static void delete(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }

        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
