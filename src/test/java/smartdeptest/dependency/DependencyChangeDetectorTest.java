package smartdeptest.dependency;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DependencyChangeDetectorTest {
    @Test
    void selectsMostRecentDependencyChangingCommit() throws Exception {
        Path repository = createRepository();
        writePom(repository, "1.0");
        git(repository, "add", "pom.xml");
        git(repository, "commit", "-m", "initial");
        writePom(repository, "2.0");
        git(repository, "add", "pom.xml");
        git(repository, "commit", "-m", "update dependency");

        DependencyChangeResult result = new DependencyChangeDetector().detect(repository);
        assertEquals("update dependency", result.getCommitMessage());
        assertEquals(DependencyChange.Type.UPDATED, result.getChanges().get(0).getChangeType());
        assertEquals("1.0", result.getChanges().get(0).getOldVersion());
        assertEquals("2.0", result.getChanges().get(0).getNewVersion());
    }

    @Test
    void reportsWhenHistoryHasNoDependencyChange() throws Exception {
        Path repository = createRepository();
        writePom(repository, "1.0");
        git(repository, "add", "pom.xml");
        git(repository, "commit", "-m", "initial");
        Files.writeString(repository.resolve("pom.xml"), "<project><properties><build.flag>x</build.flag></properties><dependencies>"
                + dependency("a", "1.0") + "</dependencies></project>", StandardCharsets.UTF_8);
        git(repository, "add", "pom.xml");
        git(repository, "commit", "-m", "build configuration");
        assertThrows(Exception.class, () -> new DependencyChangeDetector().detect(repository));
    }

    private Path createRepository() throws Exception {
        Path repository = Files.createTempDirectory("smartdeptest-git-");
        git(repository, "init");
        git(repository, "config", "user.email", "test@example.com");
        git(repository, "config", "user.name", "SmartDepTest");
        return repository;
    }

    private void writePom(Path repository, String version) throws Exception {
        Files.writeString(repository.resolve("pom.xml"), "<project><dependencies>" + dependency("a", version)
                + "</dependencies></project>", StandardCharsets.UTF_8);
    }

    private String dependency(String artifactId, String version) {
        return "<dependency><groupId>org.example</groupId><artifactId>" + artifactId + "</artifactId><version>"
                + version + "</version></dependency>";
    }

    private void git(Path repository, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true).start();
        if (process.waitFor() != 0) throw new IllegalStateException(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
