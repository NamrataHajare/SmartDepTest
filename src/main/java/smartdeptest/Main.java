package smartdeptest;

import smartdeptest.analysis.APIChangeAnalyzer;
import smartdeptest.analysis.APIChangeResult;
import smartdeptest.analysis.APIUsageAnalyzer;
import smartdeptest.analysis.APIUsageResult;
import smartdeptest.analysis.ApiChange;
import smartdeptest.analysis.DependencyApiResult;
import smartdeptest.analysis.JacocoMethodTestMapper;
import smartdeptest.dependency.DependencyChange;
import smartdeptest.dependency.DependencyChangeDetector;
import smartdeptest.dependency.DependencyChangeResult;
import smartdeptest.graph.DependencyGraphBuilder;
import smartdeptest.graph.DependencyGraphJsonExporter;
import smartdeptest.graph.DependencyGraphResult;
import org.objectweb.asm.Type;

import java.io.IOException;
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
            System.out.println("ERROR: Unable to load analysis runtime dependencies. "
                    + exception.getMessage());
        }
    }
}

final class SmartDepTestRunner {
    private static final int PREVIEW_LIMIT = Math.max(
            0, Integer.getInteger("analysis.console.preview.limit", 15));

    private SmartDepTestRunner() {}

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("MAVEN DEPENDENCY IMPACT ANALYSIS");
        System.out.println("============================================================");
        String input;
        if (args.length > 0) {
            input = args[0].trim();
        } else {
            System.out.print("Enter Maven project path:\n> ");
            input = new Scanner(System.in).nextLine().trim();
        }
        if (input.length() >= 2 && ((input.startsWith("\"") && input.endsWith("\""))
                || (input.startsWith("'") && input.endsWith("'")))) {
            input = input.substring(1, input.length() - 1);
        }
        if (input.isBlank()) {
            System.out.println("ERROR: Project directory is blank.");
            return;
        }
        Path project = Path.of(input);
        if (!Files.isDirectory(project)) {
            System.out.println("ERROR: Project directory does not exist: " + project);
            return;
        }
        try {
            long dependencyStarted = System.nanoTime();
            DependencyChangeResult changes = new DependencyChangeDetector().detect(project);
            long dependencyNanos = System.nanoTime() - dependencyStarted;
            printReport(changes);
            long impactStarted = System.nanoTime();
            APIChangeResult apiChanges = new APIChangeAnalyzer().analyze(changes);
            APIUsageResult usage = new APIUsageAnalyzer().analyze(apiChanges, project);
            printApplicationScanReport(usage);
            printImpactReport(apiChanges, usage);
            DependencyGraphResult graph = new DependencyGraphBuilder().build(apiChanges, usage);
            Path graphOutput = project.toAbsolutePath().normalize()
                    .resolve("target/smartdeptest-impact-graph.json");
            new DependencyGraphJsonExporter().write(graph, graphOutput);
            printCallGraphAndPropagation(graph, graphOutput);
            printCoverageAndRegressionSelection(graph, project);
            printSection("Execution time");
            printTable(List.of("Stage", "Elapsed time"), List.of(
                    List.of("Dependency-change detection", formatDuration(dependencyNanos)),
                    List.of("API impact analysis and reporting", formatDuration(System.nanoTime() - impactStarted))));
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message != null && message.contains("not a git repository")) {
                System.out.println("ERROR: The provided project folder is not a Git repository.");
            } else if (message != null && message.contains("Git is not installed")) {
                System.out.println("ERROR: Git is not installed or is not available in PATH.");
            } else {
                System.out.println("ERROR: " + exception.getClass().getSimpleName()
                        + (message == null || message.isBlank() ? "" : ": " + message));
            }
        } catch (LinkageError error) {
            System.out.println("ERROR: An analysis runtime dependency could not be loaded. "
                    + "Run `mvn compile` first. Details: " + error.getMessage());
        }
    }

    private static String formatDuration(long nanoseconds) {
        return String.format(java.util.Locale.ROOT, "%.3f s", nanoseconds / 1_000_000_000.0);
    }

    private static void printCallGraphAndPropagation(DependencyGraphResult result, Path graphOutput) {
        long callEdges = result.graph().getEdges().stream()
            .filter(edge -> edge.type().equals("CALLS")).count();
        printSection("Application call graph");
        printTable(List.of("Metric", "Count or path"), List.of(
            List.of("Application call-graph edges", Long.toString(callEdges)),
            List.of("Detailed graph JSON", graphOutput.toString())));
        printSection("Impact propagation");
        printMethodSet("Unique directly affected application methods", result,
            result.directlyImpactedMethods());
        printMethodSet("Unique indirectly affected callers", result,
            result.indirectlyAffectedMethods());
        printMethodSet("Unique final affected application methods", result,
            result.allAffectedMethods());
        }

    private static void printMethodSet(String label, DependencyGraphResult result, List<String> methodIds) {
        System.out.printf("%s: %d%n", label, methodIds.size());
        if (methodIds.isEmpty()) {
            System.out.println("NONE");
            return;
        }
        int displayed = Math.min(methodIds.size(), PREVIEW_LIMIT);
        for (int index = 0; index < displayed; index++) {
            System.out.println("- " + displayMethod(result, methodIds.get(index)));
        }
        if (methodIds.size() > displayed) {
            System.out.println("... " + (methodIds.size() - displayed)
                    + " additional application methods");
        }
    }

        private static void printApplicationScanReport(APIUsageResult usage) {
        APIUsageResult.AnalysisSummary summary = usage.analysisSummary();
        printSection("Application bytecode analysis");
        printTable(List.of("Metric", "Count"), List.of(
            List.of("Maven application modules discovered", Integer.toString(summary.modulesDiscovered())),
            List.of("Modules with incomplete analysis", Integer.toString(summary.modulesIncomplete())),
            List.of("Class-file candidates discovered", Integer.toString(summary.classFilesDiscovered())),
            List.of("Class files analyzed successfully", Integer.toString(summary.classFilesAnalyzed())),
            List.of("Class files that failed analysis", Integer.toString(summary.classFileFailures())),
            List.of("Class directories that could not be enumerated",
                Integer.toString(summary.classDirectoryFailures())),
            List.of("Duplicate class-file paths skipped", Integer.toString(summary.duplicateClassFilesSkipped()))));
        printPreviewTable("Application analysis diagnostic preview", List.of("Diagnostic"),
            usage.diagnostics().stream().map(diagnostic -> List.of(diagnostic)).toList(),
            "application-analysis diagnostics");
        }

    private static String displayMethod(DependencyGraphResult result, String methodId) {
        var method = result.graph().getNode(methodId);
        if (method == null) {
            return methodId;
        }
        return method.metadata().get("className") + "." + method.metadata().get("methodName")
                + method.metadata().get("descriptor");
    }

    private static void printCoverageAndRegressionSelection(DependencyGraphResult result, Path project) {
        if (result.allAffectedMethods().isEmpty()) {
            printSection("Regression test identification");
            printTable(List.of("Metric", "Value"), List.of(
                    List.of("Affected application methods", "0"),
                    List.of("Test-selection status", "Skipped"),
                    List.of("Reason", "No affected application methods were available.")));
            return;
        }

        printSection("Regression test identification");
        try {
            JacocoMethodTestMapper mapper = new JacocoMethodTestMapper();
            JacocoMethodTestMapper.CoverageResult coverage = mapper.map(project, result);
            int methodsWithTests = (int) coverage.groupedSelectedTests().stream()
                    .filter(group -> !group.selectedTests().isEmpty()).count();
            int methodsWithoutTests = (int) coverage.groupedSelectedTests().stream()
                    .filter(group -> group.selectionStatus().equals("NONE FOUND")).count();
            int methodsNotAnalyzed = (int) coverage.groupedSelectedTests().stream()
                    .filter(group -> group.selectionStatus().equals("NOT ANALYZED")).count();
            boolean staticEvidence = coverage.selectionNote()
                    .contains("statically traceable test-bytecode call paths");
                printTable(List.of("Metric", "Count"), List.of(
                    List.of("Unique affected application methods", Integer.toString(result.allAffectedMethods().size())),
                    List.of("Affected methods with selected tests", Integer.toString(methodsWithTests)),
                    List.of(staticEvidence
                            ? "Affected methods without a statically traceable test path"
                            : "Affected methods without selected tests",
                        Integer.toString(methodsWithoutTests)),
                    List.of("Affected methods not analyzed", Integer.toString(methodsNotAnalyzed)),
                    List.of("Unique selected regression test cases", Integer.toString(coverage.selectedTests().size())),
                    List.of("Test-identification diagnostics", Integer.toString(coverage.failures().size()))));
                if (!coverage.selectionNote().isBlank()) {
                System.out.println("Evidence: " + coverage.selectionNote());
                }
                printPreviewTable("Affected-method test-group preview",
                    List.of("Affected application method", "Impact", "Selection status", "Selected test cases"),
                    coverage.groupedSelectedTests().stream()
                        .map(group -> List.of(
                            displayMethod(result, group.affectedMethod()),
                            group.impactType(), group.selectionStatus(),
                            Integer.toString(group.selectedTests().size())))
                        .toList(),
                    "affected-method test groups");
                printPreviewTable("Selected regression test preview", List.of("Test case"),
                    coverage.selectedTests().stream().map(test -> List.of(test)).toList(),
                    "unique selected test cases");
                printPreviewTable("Test-identification diagnostic preview", List.of("Diagnostic"),
                    coverage.failures().stream().map(failure -> List.of(failure)).toList(),
                    "test-identification diagnostics");
            DependencyGraphResult enriched = new DependencyGraphResult(
                    result.graph(),
                    result.affectedNodes(),
                    result.impactPaths(),
                    result.directlyImpactedMethods(),
                    result.indirectlyAffectedMethods(),
                    result.allAffectedMethods(),
                    coverage.methodTestCoverage(),
                    coverage.selectedTests(),
                    coverage.groupedSelectedTests(),
                    coverage.testCoverageStatuses(),
                    result.analysisSummary(),
                    result.analysisDiagnostics());
            Path coverageOutput = project.toAbsolutePath().normalize().resolve("target/smartdeptest-coverage-selection.json");
            new DependencyGraphJsonExporter().write(enriched, coverageOutput);
            System.out.println("Coverage JSON: " + coverageOutput);
        } catch (IOException exception) {
            String message = exception.getMessage();
            System.out.println("Regression test identification unavailable: "
                    + exception.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? " (no detail message)" : ": " + message));
        }
    }

    private static void printReport(DependencyChangeResult result) {
        printSection("Dependency change detection");
        printTable(List.of("Metric", "Value"), List.of(
                List.of("Project directory", result.getProjectPath()),
                List.of("Git repository", "Available"),
                List.of("Dependency-changing commit", result.getCommitId()),
                List.of("Previous commit", result.getPreviousCommitId()),
                List.of("Commit message", result.getCommitMessage())));

        Map<DependencyChange.Type, Long> counts = new EnumMap<>(DependencyChange.Type.class);
        result.getChanges().forEach(change -> counts.merge(change.getChangeType(), 1L, Long::sum));
        Set<String> dependencyIdentities = new LinkedHashSet<>();
        result.getChanges().forEach(change -> dependencyIdentities.add(change.getDependencyKey()));
        List<List<String>> summary = new ArrayList<>();
        summary.add(List.of("POM paths with dependency changes", Integer.toString(result.getChangedPomFiles().size())));
        summary.add(List.of("Dependency-change records", Integer.toString(result.getChanges().size())));
        summary.add(List.of("Unique dependency identities (group:artifact)",
                Integer.toString(dependencyIdentities.size())));
        for (DependencyChange.Type type : DependencyChange.Type.values()) {
            summary.add(List.of(type + " dependency-change records",
                    Long.toString(counts.getOrDefault(type, 0L))));
        }
        printSection("Dependency-change summary");
        printTable(List.of("Metric", "Count"), summary);

        List<List<String>> pomRows = result.getChangedPomFiles().stream()
                .map(path -> List.of(path)).toList();
        printPreviewTable("POM paths with dependency changes", List.of("POM path"), pomRows,
                "POM-path records");

        List<List<String>> changeRows = result.getChanges().stream()
                .map(change -> List.of(
                        change.getChangeType().name(),
                        change.getDependencyKey(),
                        versionTransition(change),
                        change.getPomPath()))
                .toList();
        printPreviewTable("Dependency-change records",
                List.of("Change type", "Dependency", "Version transition", "POM path"),
                changeRows, "dependency-change records");
        System.out.println("Dependency change detection completed.");
    }

    private static String versionTransition(DependencyChange change) {
        String oldVersion = change.getOldVersion().isBlank() ? "(none)" : change.getOldVersion();
        String newVersion = change.getNewVersion().isBlank() ? "(none)" : change.getNewVersion();
        if (change.getChangeType() == DependencyChange.Type.SCOPE_CHANGED) {
            return change.getOldScope() + " -> " + change.getNewScope();
        }
        return oldVersion + " -> " + newVersion;
    }

    private static void printSection(String title) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println(title.toUpperCase(java.util.Locale.ROOT));
        System.out.println("============================================================");
    }

    private static void printPreviewTable(String title, List<String> headers,
                                          List<List<String>> rows, String entityName) {
        printSection(title);
        int displayed = Math.min(rows.size(), PREVIEW_LIMIT);
        System.out.printf("Showing %d of %d %s.%n", displayed, rows.size(), entityName);
        printTable(headers, rows.subList(0, displayed));
        if (rows.size() > displayed) {
            System.out.printf("... %d additional %s.%n", rows.size() - displayed, entityName);
        }
    }

    private static void printTable(List<String> headers, List<List<String>> rows) {
        int[] widths = new int[headers.size()];
        for (int column = 0; column < headers.size(); column++) {
            widths[column] = Math.min(52, headers.get(column).length());
            for (List<String> row : rows) {
                if (column < row.size()) {
                    widths[column] = Math.min(52,
                            Math.max(widths[column], oneLine(row.get(column)).length()));
                }
            }
        }
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append('+');
        }
        System.out.println(border);
        printTableRow(headers, widths);
        System.out.println(border);
        for (List<String> row : rows) {
            printTableRow(row, widths);
        }
        System.out.println(border);
    }

    private static void printTableRow(List<String> values, int[] widths) {
        StringBuilder row = new StringBuilder("|");
        for (int column = 0; column < widths.length; column++) {
            String value = column < values.size() ? oneLine(values.get(column)) : "";
            row.append(' ').append(tableCell(value, widths[column])).append(" |");
        }
        System.out.println(row);
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    static void printImpactReport(APIChangeResult apiChanges, APIUsageResult usage) {
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
                    printTable(List.of("Library API-diff metric", "Count"), List.of(
                        List.of("API-diff records", Integer.toString(dependency.changes().size())),
                        List.of("Added API-diff records", Long.toString(added)),
                        List.of("Removed API-diff records", Long.toString(removed)),
                        List.of("Modified API-diff records", Long.toString(modified))));
                    System.out.println("API-diff counts describe library members, not application usages.");
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
                            location.className().replace('/', '.') + "."
                                    + location.methodName() + location.methodDescriptor(),
                            location.instructionType()));
                }
            }
            List<ImpactRow> sortedRows = impactRows.stream()
                    .sorted(Comparator.comparing(ImpactRow::applicationMethod)
                            .thenComparing(ImpactRow::changedApi)
                            .thenComparing(ImpactRow::instructionType))
                    .toList();
                    long impactedMethodCount = sortedRows.stream()
                        .map(ImpactRow::applicationMethod).distinct().count();
                    System.out.println("Unique affected application methods: " + impactedMethodCount);
                    System.out.println("Unique API-impact records: " + sortedRows.size()
                        + " (deduplicated by changed API, application method, and instruction type)");
            if (sortedRows.isEmpty()) {
                System.out.println("NONE");
            } else {
                System.out.println("#   | Changed API member (descriptor)                  | Application method                              | Instruction");
                System.out.println("----+---------------------------------------------------+--------------------------------------------------+------------------");
                int displayed = Math.min(sortedRows.size(), PREVIEW_LIMIT);
                for (int index = 0; index < displayed; index++) {
                    ImpactRow row = sortedRows.get(index);
                    System.out.printf("%3d | %-49s | %-48s | %s%n", index + 1,
                            tableCell(row.changedApi(), 49), tableCell(row.applicationMethod(), 48),
                            row.instructionType());
                }
                if (sortedRows.size() > displayed) {
                        System.out.println("... " + (sortedRows.size() - displayed)
                            + " additional API-impact records");
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
