package smartdeptest.graph;

import java.util.ArrayList;
import java.util.List;

public record DependencyGraphResult(DependencyGraph graph, List<String> affectedNodes,
                                    List<ImpactPath> impactPaths,
                                    List<String> directlyImpactedMethods,
                                    List<String> indirectlyAffectedMethods,
                                    List<String> allAffectedMethods,
                                    List<MethodTestCoverage> methodTestMapping,
                                    List<String> selectedTests,
                                    List<AffectedMethodTestGroup> groupedSelectedTests,
                                    List<TestCoverageStatus> testCoverageStatuses,
                                    smartdeptest.analysis.APIUsageResult.AnalysisSummary analysisSummary,
                                    List<String> analysisDiagnostics) {
    public DependencyGraphResult(DependencyGraph graph, List<String> affectedNodes,
                                 List<ImpactPath> impactPaths) {
        this(graph, affectedNodes, impactPaths, List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(),
            smartdeptest.analysis.APIUsageResult.AnalysisSummary.empty(), List.of());
    }

    public DependencyGraphResult(DependencyGraph graph, List<String> affectedNodes,
                                 List<ImpactPath> impactPaths,
                                 List<String> directlyImpactedMethods,
                                 List<String> indirectlyAffectedMethods,
                                 List<String> allAffectedMethods) {
        this(graph, affectedNodes, impactPaths, directlyImpactedMethods,
            indirectlyAffectedMethods, allAffectedMethods, List.of(), List.of(), List.of(), List.of(),
            smartdeptest.analysis.APIUsageResult.AnalysisSummary.empty(), List.of());
    }

    public DependencyGraphResult(DependencyGraph graph, List<String> affectedNodes,
                                 List<ImpactPath> impactPaths,
                                 List<String> directlyImpactedMethods,
                                 List<String> indirectlyAffectedMethods,
                                 List<String> allAffectedMethods,
                                 List<MethodTestCoverage> methodTestMapping,
                                 List<String> selectedTests,
                                 List<AffectedMethodTestGroup> groupedSelectedTests) {
        this(graph, affectedNodes, impactPaths, directlyImpactedMethods, indirectlyAffectedMethods,
            allAffectedMethods, methodTestMapping, selectedTests, groupedSelectedTests, List.of(),
            smartdeptest.analysis.APIUsageResult.AnalysisSummary.empty(), List.of());
        }

        public DependencyGraphResult(DependencyGraph graph, List<String> affectedNodes,
                     List<ImpactPath> impactPaths,
                     List<String> directlyImpactedMethods,
                     List<String> indirectlyAffectedMethods,
                     List<String> allAffectedMethods,
                     List<MethodTestCoverage> methodTestMapping,
                     List<String> selectedTests,
                     List<AffectedMethodTestGroup> groupedSelectedTests,
                     List<TestCoverageStatus> testCoverageStatuses) {
        this(graph, affectedNodes, impactPaths, directlyImpactedMethods, indirectlyAffectedMethods,
            allAffectedMethods, methodTestMapping, selectedTests, groupedSelectedTests,
            testCoverageStatuses, smartdeptest.analysis.APIUsageResult.AnalysisSummary.empty(), List.of());
    }

    public DependencyGraphResult {
        affectedNodes = List.copyOf(affectedNodes);
        impactPaths = List.copyOf(impactPaths);
        directlyImpactedMethods = List.copyOf(directlyImpactedMethods);
        indirectlyAffectedMethods = List.copyOf(indirectlyAffectedMethods);
        allAffectedMethods = List.copyOf(allAffectedMethods);
        methodTestMapping = List.copyOf(methodTestMapping);
        selectedTests = List.copyOf(selectedTests);
        groupedSelectedTests = List.copyOf(groupedSelectedTests);
        testCoverageStatuses = List.copyOf(testCoverageStatuses);
        analysisSummary = analysisSummary == null
            ? smartdeptest.analysis.APIUsageResult.AnalysisSummary.empty() : analysisSummary;
        analysisDiagnostics = analysisDiagnostics == null ? List.of() : List.copyOf(analysisDiagnostics);
    }

    public record MethodTestCoverage(String applicationMethod, String testClass, String testMethod) {
        public MethodTestCoverage {
            applicationMethod = applicationMethod == null ? "" : applicationMethod;
            testClass = testClass == null ? "" : testClass;
            testMethod = testMethod == null ? "" : testMethod;
        }
    }

    public record TestCoverageStatus(String testClass, String testMethod, String status, String note) {
        public TestCoverageStatus {
            testClass = testClass == null ? "" : testClass;
            testMethod = testMethod == null ? "" : testMethod;
            status = status == null || status.isBlank() ? "N/A" : status;
            note = note == null ? "" : note;
        }
    }

    public record SelectedTest(String testClass, String testMethod, List<String> affectedMethods) {
        public SelectedTest {
            affectedMethods = affectedMethods == null ? List.of() : List.copyOf(new ArrayList<>(affectedMethods));
        }
    }

    public record AffectedMethodTestGroup(String affectedMethod, String impactType, List<String> selectedTests,
                                          String selectionStatus, String selectionNote) {
        public AffectedMethodTestGroup(String affectedMethod, String impactType, List<String> selectedTests) {
            this(affectedMethod, impactType, selectedTests,
                    selectedTests == null || selectedTests.isEmpty() ? "N/A" : "SELECTED", "");
        }

        public AffectedMethodTestGroup {
            selectedTests = selectedTests == null ? List.of() : List.copyOf(new ArrayList<>(selectedTests));
            selectionStatus = selectionStatus == null || selectionStatus.isBlank() ? "N/A" : selectionStatus;
            selectionNote = selectionNote == null ? "" : selectionNote;
        }
    }
}