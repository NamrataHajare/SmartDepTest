package smartdeptest;

import smartdeptest.dependency.DependencyChange;
import smartdeptest.dependency.DependencyChangeDetector;
import smartdeptest.dependency.DependencyChangeResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public final class Main {
    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("SMARTDEPTEST");
        System.out.println("COMPONENT 1 - DEPENDENCY CHANGE DETECTOR");
        System.out.println("============================================================");
        System.out.println();
        System.out.print("Enter Maven project path:\n> ");
        String input = new Scanner(System.in).nextLine().trim();
        if (input.isBlank()) { System.out.println("ERROR: Project directory does not exist."); return; }
        Path project = Path.of(input);
        if (!Files.isDirectory(project)) { System.out.println("ERROR: Project directory does not exist."); return; }
        try {
            DependencyChangeResult result = new DependencyChangeDetector().detect(project);
            printReport(result);
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message != null && message.contains("not a git repository")) {
                System.out.println("ERROR: The provided project folder is not a Git repository.");
            } else if (message != null && message.contains("Git is not installed")) {
                System.out.println("ERROR: Git is not installed or is not available in PATH.");
            } else {
                System.out.println("ERROR: " + (message == null ? "Dependency detection failed." : message));
            }
        }
    }

    private static void printReport(DependencyChangeResult result) {
        System.out.println();
        System.out.println("Project: " + result.getProjectPath());
        System.out.println("Git Repository: Detected");
        System.out.println();
        System.out.println("------------------------------------------------------------");
        System.out.println("GIT ANALYSIS");
        System.out.println("------------------------------------------------------------");
        System.out.println("Dependency-changing commit: " + result.getCommitId());
        System.out.println("Previous commit: " + result.getPreviousCommitId());
        System.out.println("Commit message: " + result.getCommitMessage());
        System.out.println();
        System.out.println("POM files containing changes:");
        result.getChangedPomFiles().forEach(path -> System.out.println("- " + path));
        System.out.println();
        System.out.println("------------------------------------------------------------");
        System.out.println("DEPENDENCY CHANGES");
        System.out.println("------------------------------------------------------------");
        for (DependencyChange change : result.getChanges()) {
            System.out.println();
            System.out.println("[" + change.getChangeType() + "]");
            System.out.println("Dependency: " + change.getDependencyKey());
            System.out.println("POM: " + change.getPomPath());
            if (!change.getOldVersion().isEmpty()) System.out.println("Previous Version: " + change.getOldVersion());
            if (!change.getNewVersion().isEmpty()) System.out.println("New Version: " + change.getNewVersion());
            if (change.getChangeType() == DependencyChange.Type.SCOPE_CHANGED) {
                System.out.println("Previous Scope: " + change.getOldScope());
                System.out.println("New Scope: " + change.getNewScope());
            }
        }
        Map<DependencyChange.Type, Long> counts = new EnumMap<>(DependencyChange.Type.class);
        result.getChanges().forEach(change -> counts.merge(change.getChangeType(), 1L, Long::sum));
        System.out.println();
        System.out.println("------------------------------------------------------------");
        System.out.println("SUMMARY");
        System.out.println("------------------------------------------------------------");
        System.out.println("Changed POM files: " + result.getChangedPomFiles().size());
        Set<String> changedDependencies = new LinkedHashSet<>();
        result.getChanges().forEach(change -> changedDependencies.add(change.getDependencyKey()));
        System.out.println("Changed dependencies: " + String.join(", ", changedDependencies));
        System.out.println("Added dependencies: " + counts.getOrDefault(DependencyChange.Type.ADDED, 0L));
        System.out.println("Removed dependencies: " + counts.getOrDefault(DependencyChange.Type.REMOVED, 0L));
        System.out.println("Updated dependencies: " + counts.getOrDefault(DependencyChange.Type.UPDATED, 0L));
        long other = result.getChanges().stream().filter(change -> change.getChangeType() != DependencyChange.Type.ADDED
                && change.getChangeType() != DependencyChange.Type.REMOVED && change.getChangeType() != DependencyChange.Type.UPDATED).count();
        System.out.println("Other dependency changes: " + other);
        System.out.println();
        System.out.println("Dependency Change Detection Completed");
    }
}
