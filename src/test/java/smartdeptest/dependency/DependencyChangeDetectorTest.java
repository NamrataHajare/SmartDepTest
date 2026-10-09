package smartdeptest.dependency;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        Files.writeString(repository.resolve("notes.txt"), "unrelated commit", StandardCharsets.UTF_8);
        git(repository, "add", "notes.txt");
        git(repository, "commit", "-m", "unrelated source change");

        DependencyChangeResult result = new DependencyChangeDetector().detect(repository);
        assertEquals("update dependency", result.getCommitMessage());
        assertEquals(DependencyChange.Type.UPDATED, result.getChanges().get(0).getChangeType());
        assertEquals("1.0", result.getChanges().get(0).getOldVersion());
        assertEquals("2.0", result.getChanges().get(0).getNewVersion());
    }

    @Test
    void selectsDependencyUpdateWhenItIsTheLatestCommit() throws Exception {
        Path repository = createRepository();
        writePom(repository, "1.0");
        git(repository, "add", "pom.xml");
        git(repository, "commit", "-m", "initial");
        writePom(repository, "2.0");
        git(repository, "add", "pom.xml");
        git(repository, "commit", "-m", "latest dependency update");

        DependencyChangeResult result = new DependencyChangeDetector().detect(repository);

        assertEquals("latest dependency update", result.getCommitMessage());
        assertEquals(DependencyChange.Type.UPDATED, result.getChanges().get(0).getChangeType());
    }

    @Test
    void findsDependencyUpdateMoreThanTenPomCommitsBack() throws Exception {
        Path repository = createRepository();
        writePom(repository, "1.0");
        git(repository, "add", "pom.xml");
        git(repository, "commit", "-m", "initial");
        writePom(repository, "2.0");
        git(repository, "add", "pom.xml");
        git(repository, "commit", "-m", "update dependency");

        for (int index = 0; index < 12; index++) {
            String pom = "<project><properties><build.flag>" + index + "</build.flag></properties>"
                    + "<dependencies>" + dependency("a", "2.0") + "</dependencies></project>";
            Files.writeString(repository.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
            git(repository, "add", "pom.xml");
            git(repository, "commit", "-m", "unrelated POM change " + index);
        }

        DependencyChangeResult result = new DependencyChangeDetector().detect(repository);

        assertEquals("update dependency", result.getCommitMessage());
        assertEquals("1.0", result.getChanges().get(0).getOldVersion());
        assertEquals("2.0", result.getChanges().get(0).getNewVersion());
    }

    @Test
    void detectsChangesInModulePom() throws Exception {
        Path repository = createRepository();
        Files.createDirectories(repository.resolve("module-a"));
        Files.createDirectories(repository.resolve("module-b"));
        writePom(repository, "1.0");
        Files.writeString(repository.resolve("module-a/pom.xml"),
                "<project><dependencies>" + dependency("module-library", "1.0") + "</dependencies></project>",
                StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("module-b/pom.xml"),
            "<project><dependencies>" + dependency("other-library", "1.0") + "</dependencies></project>",
            StandardCharsets.UTF_8);
        git(repository, "add", "pom.xml", "module-a/pom.xml", "module-b/pom.xml");
        git(repository, "commit", "-m", "initial modules");

        Files.writeString(repository.resolve("module-a/pom.xml"),
                "<project><dependencies>" + dependency("module-library", "2.0") + "</dependencies></project>",
                StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("module-b/pom.xml"),
            "<project><dependencies>" + dependency("other-library", "2.0") + "</dependencies></project>",
            StandardCharsets.UTF_8);
        git(repository, "add", "module-a/pom.xml", "module-b/pom.xml");
        git(repository, "commit", "-m", "update module dependency");

        DependencyChangeResult result = new DependencyChangeDetector().detect(repository);

        assertEquals(2, result.getChanges().size());
        assertEquals(List.of("module-a/pom.xml", "module-b/pom.xml"), result.getChangedPomFiles());
        assertEquals(List.of("module-a/pom.xml", "module-b/pom.xml"),
            result.getChanges().stream().map(DependencyChange::getPomPath).sorted().toList());
        assertEquals("1.0", result.getChanges().get(0).getOldVersion());
        assertEquals("2.0", result.getChanges().get(0).getNewVersion());
    }

    @Test
    void continuesAfterOneChangedPomCannotBeAnalyzed() throws Exception {
        Path repository = createRepository();
        Files.createDirectories(repository.resolve("module"));
        writePom(repository, "1.0");
        Files.writeString(repository.resolve("module/pom.xml"),
                "<project><dependencies>" + dependency("module-library", "1.0")
                        + "</dependencies></project>", StandardCharsets.UTF_8);
        git(repository, "add", "pom.xml", "module/pom.xml");
        git(repository, "commit", "-m", "initial");

        writePom(repository, "2.0");
        Files.writeString(repository.resolve("module/pom.xml"), "<project><dependencies>",
                StandardCharsets.UTF_8);
        git(repository, "add", "pom.xml", "module/pom.xml");
        git(repository, "commit", "-m", "update and malformed module pom");

        DependencyChangeResult result = new DependencyChangeDetector().detect(repository);

        assertEquals(1, result.getChanges().size());
        assertEquals(DependencyChange.Type.UPDATED, result.getChanges().get(0).getChangeType());
        assertEquals(1, result.getDiagnostics().size());
        assertTrue(result.getDiagnostics().get(0).contains("module/pom.xml"));
    }

    @Test
    void comparesMergeCommitWithItsFirstParent() throws Exception {
        Path repository = createRepository();
        writePom(repository, "1.0");
        git(repository, "add", "pom.xml");
        git(repository, "commit", "-m", "initial");
        git(repository, "branch", "feature");

        Files.writeString(repository.resolve("notes.txt"), "main branch change", StandardCharsets.UTF_8);
        git(repository, "add", "notes.txt");
        git(repository, "commit", "-m", "unrelated main change");

        git(repository, "checkout", "feature");
        writePom(repository, "2.0");
        git(repository, "add", "pom.xml");
        git(repository, "commit", "-m", "feature dependency update");

        git(repository, "checkout", "main");
        git(repository, "merge", "--no-ff", "feature", "-m", "merge dependency update");

        DependencyChangeResult result = new DependencyChangeDetector().detect(repository);

        assertEquals("merge dependency update", result.getCommitMessage());
        assertEquals("1.0", result.getChanges().get(0).getOldVersion());
        assertEquals("2.0", result.getChanges().get(0).getNewVersion());
    }

    @Test
    void reportsWhenShallowHistoryMayHideOlderChanges() throws Exception {
        Path source = createRepository();
        writePom(source, "1.0");
        git(source, "add", "pom.xml");
        git(source, "commit", "-m", "initial");
        writePom(source, "2.0");
        git(source, "add", "pom.xml");
        git(source, "commit", "-m", "dependency update");

        Path clone = Files.createTempDirectory("smartdeptest-shallow-").resolve("clone");
        git(source, "clone", "--depth=1", source.toUri().toString(), clone.toString());

        Exception exception = assertThrows(Exception.class, () -> new DependencyChangeDetector().detect(clone));

        assertTrue(exception.getMessage().contains("repository is shallow"));
        assertTrue(exception.getMessage().contains("fetch more history"));
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
        git(repository, "init", "-b", "main");
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
