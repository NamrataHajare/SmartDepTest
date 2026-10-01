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
import org.objectweb.asm.Type;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public final class Main {
    public static void main(String[] args) {
        try {
            Path classesDirectory = Path.of(Main.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            Path dependencyDirectory = classesDirectory.getParent().resolve("dependency");
            if (!Files.isDirectory(dependencyDirectory)) {
                throw new IllegalStateException("Runtime dependencies are not staged. Run `mvn compile` first.");
            }

            List<URL> classpath = new ArrayList<>();
            classpath.add(classesDirectory.toUri().toURL());
            try (var dependencies = Files.list(dependencyDirectory)) {
                for (Path dependency : dependencies.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                    classpath.add(dependency.toUri().toURL());
                }
            }

            try (URLClassLoader loader = new URLClassLoader(classpath.toArray(URL[]::new),
                    ClassLoader.getPlatformClassLoader())) {
                Thread thread = Thread.currentThread();
                ClassLoader previousLoader = thread.getContextClassLoader();
                thread.setContextClassLoader(loader);
                try {
                    Class<?> runner = Class.forName("smartdeptest.SmartDepTestRunner", true, loader);
                    var method = runner.getDeclaredMethod("main", String[].class);
                    method.setAccessible(true);
                    method.invoke(null, (Object) args);
                } finally {
                    thread.setContextClassLoader(previousLoader);
                }
            }
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            (cause == null ? exception : cause).printStackTrace();
        } catch (Exception | LinkageError exception) {
            System.out.println("ERROR: Unable to load SmartDepTest runtime dependencies. "
                    + exception.getMessage());
        }
    }
}

final class SmartDepTestRunner {
    private SmartDepTestRunner() {}

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("SMARTDEPTEST");
        System.out.println("COMPONENT 1 - DEPENDENCY CHANGE DETECTOR");
        System.out.println("============================================================");
        System.out.println();
        String input;
        if (args.length > 0) {
            input = args[0].trim();
        } else {
            System.out.print("Enter Maven project path:\n> ");
            input = new Scanner(System.in).nextLine().trim();
        }
        if (input.isBlank()) { System.out.println("ERROR: Project directory does not exist."); return; }
        Path project = Path.of(input);
        if (!Files.isDirectory(project)) { System.out.println("ERROR: Project directory does not exist."); return; }
        try {
            long dependencyStarted = System.nanoTime();
            DependencyChangeResult result = new DependencyChangeDetector().detect(project);
            long dependencyNanos = System.nanoTime() - dependencyStarted;
            printReport(result);
            long apiImpactStarted = System.nanoTime();
            APIChangeResult apiChanges = new APIChangeAnalyzer().analyze(result);
            APIUsageResult usage = new APIUsageAnalyzer().analyze(apiChanges, project);
            printImpactReport(apiChanges, usage);
            System.out.printf("Dependency change detection time: %d ms.%n", dependencyNanos / 1_000_000);
            System.out.printf("Total API impact analysis time: %d ms.%n",
                (System.nanoTime() - apiImpactStarted) / 1_000_000);
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message != null && message.contains("not a git repository")) {
                System.out.println("ERROR: The provided project folder is not a Git repository.");
            } else if (message != null && message.contains("Git is not installed")) {
                System.out.println("ERROR: Git is not installed or is not available in PATH.");
            } else {
                System.out.println("ERROR: " + (message == null ? "Dependency detection failed." : message));
            }
        } catch (LinkageError error) {
            System.out.println("ERROR: A runtime dependency could not be loaded. Run `mvn compile` first "
                + "to stage the required dependencies. Details: " + error.getMessage());
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
            System.out.println("Declaration: " + (dependency.dependencyManagement()
                    ? "DEPENDENCY_MANAGEMENT" : "DIRECT_POM_DECLARATION"));
            System.out.println("Scope: " + dependency.oldScope() + " -> " + dependency.newScope());
            System.out.println("Type: " + dependency.oldType() + " -> " + dependency.newType());
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
                System.out.println("Dependency API diff: " + dependency.changes().size() + " changes (added " + added
                    + ", removed " + removed + ", modified " + modified + ")");
                System.out.println("Note: This count is the library-wide diff, not the number of impacted application methods.");
            if (!dependency.message().isBlank()) {
                System.out.println("Analysis note: " + conciseReason(dependency.message()));
            }
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
                    .filter(APIUsageResult.UsageFinding::used).toList();
            Set<String> memberUsages = new LinkedHashSet<>();
            for (APIUsageResult.UsageFinding finding : usedFindings) {
                if (finding.change().memberName().isBlank()) continue;
                for (APIUsageResult.UsageLocation location : finding.locations()) {
                    memberUsages.add(usageKey(finding.change().className(), location));
                }
            }
            Set<ImpactRow> impactRows = new LinkedHashSet<>();
            for (APIUsageResult.UsageFinding finding : usedFindings) {
                ApiChange change = finding.change();
                for (APIUsageResult.UsageLocation location : finding.locations()) {
                    if (change.memberName().isBlank() && memberUsages.contains(usageKey(change.className(), location))) {
                        continue;
                    }
                    impactRows.add(new ImpactRow(renderApiChange(change),
                            simpleClassName(location.className()) + "."
                                    + location.methodName() + location.methodDescriptor(),
                            location.instructionType()));
                }
            }
            List<ImpactRow> sortedRows = impactRows.stream()
                    .sorted(Comparator.comparing(ImpactRow::applicationMethod)
                            .thenComparing(ImpactRow::changedApi)
                            .thenComparing(ImpactRow::instructionType))
                    .toList();
                    long impactedMethodCount = sortedRows.stream().map(ImpactRow::applicationMethod).distinct().count();
                    System.out.println("Impacted application methods: " + impactedMethodCount
                        + " (" + sortedRows.size() + " API references)");
            if (sortedRows.isEmpty()) {
                System.out.println("NONE");
            } else {
                System.out.println("#   | Changed API member (descriptor)                  | Application method                              | Instruction");
                System.out.println("----+---------------------------------------------------+--------------------------------------------------+------------------");
                int displayed = Math.min(sortedRows.size(), 15);
                for (int index = 0; index < displayed; index++) {
                    ImpactRow row = sortedRows.get(index);
                    System.out.printf("%3d | %-49s | %-48s | %s%n", index + 1,
                            tableCell(row.changedApi(), 49), tableCell(row.applicationMethod(), 48),
                            row.instructionType());
                }
                if (sortedRows.size() > displayed) {
                    System.out.println("... and " + (sortedRows.size() - displayed) + " more impacted method(s).");
                }
            }
        }
        System.out.println();
        System.out.println("Impact describes static-analysis evidence only; it does not claim a runtime failure.");
    }

    private static String renderApiChange(ApiChange change) {
        String owner = simpleClassName(change.className());
        if (change.memberName().isBlank()) return owner + " [" + change.kind() + "]";
        if (change.kind().name().startsWith("FIELD_")) {
            String oldType = change.oldDescriptor().isBlank() ? "" : readableType(Type.getType(change.oldDescriptor()));
            String newType = change.newDescriptor().isBlank() ? "" : readableType(Type.getType(change.newDescriptor()));
            String type = oldType.isBlank() ? newType : oldType;
            if (!oldType.isBlank() && !newType.isBlank() && !oldType.equals(newType)) {
                type += " -> " + newType;
            }
            return owner + "." + change.memberName() + (type.isBlank() ? "" : ": " + type)
                    + " [" + change.kind() + "]";
        }

        String name = "<init>".equals(change.memberName()) ? owner : owner + "." + change.memberName();
        String oldSignature = change.oldDescriptor().isBlank() ? ""
                : readableMethod(name, change.oldDescriptor());
        String newSignature = change.newDescriptor().isBlank() ? ""
                : readableMethod(name, change.newDescriptor());
        String signature = oldSignature.isBlank() ? newSignature : oldSignature;
        if (!oldSignature.isBlank() && !newSignature.isBlank() && !oldSignature.equals(newSignature)) {
            signature += " -> " + newSignature;
        }
        if (!oldSignature.isBlank() && !newSignature.isBlank()
                && change.oldDescriptor().substring(0, change.oldDescriptor().indexOf(')') + 1)
                .equals(change.newDescriptor().substring(0, change.newDescriptor().indexOf(')') + 1))) {
            Type oldReturn = Type.getReturnType(change.oldDescriptor());
            Type newReturn = Type.getReturnType(change.newDescriptor());
            if (!oldReturn.equals(newReturn)) {
                signature += " (return " + readableType(oldReturn) + " -> " + readableType(newReturn) + ")";
            }
        }
        return signature + " [" + change.kind() + "]";
    }

    private static String readableMethod(String name, String descriptor) {
        return name + "(" + java.util.Arrays.stream(Type.getArgumentTypes(descriptor))
            .map(SmartDepTestRunner::readableType)
            .collect(java.util.stream.Collectors.joining(", ")) + ")";
    }

    private static String readableType(Type type) {
        if (type.getSort() == Type.ARRAY) {
            return readableType(type.getElementType()) + "[]".repeat(type.getDimensions());
        }
        String name = type.getClassName().replace('$', '.');
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String simpleClassName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
    }

    private static String usageKey(String owner, APIUsageResult.UsageLocation location) {
        return owner + "|" + location.className() + "|" + location.methodName() + location.methodDescriptor();
    }

    private static String tableCell(String value, int width) {
        if (value.length() <= width) return value;
        return value.substring(0, width - 3) + "...";
    }

    private record ImpactRow(String changedApi, String applicationMethod, String instructionType) {}

    private static APIUsageResult.DependencyImpact findImpact(APIUsageResult usage,
                                                               DependencyApiResult dependency) {
        return usage.dependencies().stream()
                .filter(impact -> impact.dependencyKey().equals(dependency.dependencyKey()))
                .filter(impact -> impact.oldVersion().equals(dependency.oldVersion()))
                .filter(impact -> impact.newVersion().equals(dependency.newVersion()))
                .filter(impact -> impact.oldClassifier().equals(dependency.oldClassifier()))
                .filter(impact -> impact.newClassifier().equals(dependency.newClassifier()))
                .filter(impact -> impact.pomPath().equals(dependency.pomPath()))
                .filter(impact -> impact.dependencyManagement() == dependency.dependencyManagement())
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
