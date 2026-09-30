package smartdeptest;

import smartdeptest.analysis.APIChangeAnalyzer;
import smartdeptest.analysis.APIChangeResult;
import smartdeptest.analysis.APIUsageAnalyzer;
import smartdeptest.analysis.APIUsageResult;
import smartdeptest.analysis.ApiChange;
import smartdeptest.analysis.DependencyApiResult;
import smartdeptest.dependency.DependencyChange;
import smartdeptest.dependency.DependencyChangeDetector;
import smartdeptest.dependency.DependencyChangeResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
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
            APIChangeResult apiChanges = new APIChangeAnalyzer().analyze(result);
            APIUsageResult usage = new APIUsageAnalyzer().analyze(apiChanges, project);
            printImpactReport(apiChanges, usage);
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

    private static void printImpactReport(APIChangeResult apiChanges, APIUsageResult usage) {
        System.out.println();
        System.out.println("------------------------------------------------------------");
        System.out.println("DEPENDENCY API IMPACT");
        System.out.println("------------------------------------------------------------");
        for (DependencyApiResult dependency : apiChanges.dependencies()) {
            System.out.println();
            System.out.println("Dependency: " + dependency.dependencyKey());
            System.out.println("Version: " + dependency.oldVersion() + " -> " + dependency.newVersion());
            if (!dependency.pomPath().isBlank()) System.out.println("Target POM: " + dependency.pomPath());
            APIUsageResult.DependencyImpact impact = findImpact(usage, dependency);
            if (dependency.status() == DependencyApiResult.Status.UNAVAILABLE) {
                System.out.println("Impact: ANALYSIS_UNAVAILABLE");
                System.out.println("Reason: " + conciseReason(dependency.message()));
                continue;
            }
            long added = dependency.changes().stream()
                    .filter(change -> change.kind().name().endsWith("_ADDED")).count();
            long removed = dependency.changes().stream()
                    .filter(change -> change.kind().name().endsWith("_REMOVED")).count();
            long modified = dependency.changes().stream()
                    .filter(change -> change.kind().name().endsWith("_MODIFIED")).count();
            System.out.println("API changes: " + dependency.changes().size() + " (added " + added
                    + ", removed " + removed + ", modified " + modified + ")");
            if (impact == null) {
                System.out.println("Impact: ANALYSIS_UNAVAILABLE");
                System.out.println("Reason: No usage-analysis result was produced.");
                continue;
            }
            System.out.println("Impact: " + impact.classification());
            if (impact.classification() == APIUsageResult.Classification.ANALYSIS_UNAVAILABLE) {
                System.out.println("Reason: " + conciseReason(impact.message()));
                continue;
            }
            List<APIUsageResult.UsageFinding> usedFindings = impact.findings().stream()
                    .filter(APIUsageResult.UsageFinding::used)
                    .filter(finding -> finding.change().potentiallyIncompatible()).toList();
            if (!usedFindings.isEmpty()) {
                System.out.println("Used incompatible APIs:");
            }
            for (APIUsageResult.UsageFinding finding : usedFindings) {
                ApiChange change = finding.change();
                System.out.println("- " + change.className()
                        + (change.memberName().isBlank() ? "" : "." + change.memberName()));
                for (APIUsageResult.UsageLocation location : finding.locations()) {
                    System.out.println("  Used by: " + location.className() + "." + location.methodName()
                            + " at " + location.sourcePath() + ":" + location.line());
                }
            }
        }
        System.out.println();
        System.out.println("Impact describes static-analysis evidence only; it does not claim a runtime failure.");
    }

    private static APIUsageResult.DependencyImpact findImpact(APIUsageResult usage,
                                                               DependencyApiResult dependency) {
        return usage.dependencies().stream()
                .filter(impact -> impact.dependencyKey().equals(dependency.dependencyKey()))
                .filter(impact -> impact.oldVersion().equals(dependency.oldVersion()))
                .filter(impact -> impact.newVersion().equals(dependency.newVersion()))
                .findFirst().orElse(null);
    }

    private static String conciseReason(String message) {
        if (message == null || message.isBlank()) return "Analysis could not be completed.";
        List<String> errors = message.lines().map(String::trim)
                .filter(line -> line.startsWith("[ERROR]"))
                .filter(line -> !line.contains("[Help "))
                .limit(3).toList();
        if (!errors.isEmpty()) return String.join(" ", errors);
        return message.lines().map(String::trim).filter(line -> !line.isBlank()).limit(2)
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
