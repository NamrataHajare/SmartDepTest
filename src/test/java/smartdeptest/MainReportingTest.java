package smartdeptest;

import org.junit.jupiter.api.Test;
import smartdeptest.analysis.APIChangeResult;
import smartdeptest.analysis.APIUsageResult;
import smartdeptest.analysis.ApiChange;
import smartdeptest.analysis.DependencyApiResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MainReportingTest {
    @Test
    void apiImpactPreviewReportsRemainingRecordsNotMethods() {
        String previousLimit = System.getProperty("analysis.console.preview.limit");
        PrintStream previousOutput = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setProperty("analysis.console.preview.limit", "1");
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        try {
            ApiChange firstChange = change("first");
            ApiChange secondChange = change("second");
            DependencyApiResult dependency = new DependencyApiResult(
                    "org.example", "library", "1", "2", "", "", "compile", "compile",
                    "jar", "jar", false, "pom.xml", "", "", "", "",
                    DependencyApiResult.Status.ANALYZED, "", List.of(firstChange, secondChange));
            APIChangeResult changes = new APIChangeResult("project", List.of(dependency));
            APIUsageResult.UsageLocation location = new APIUsageResult.UsageLocation(
                    "org/example/App", "invoke", "()V", "INVOKEVIRTUAL");
            APIUsageResult.DependencyImpact impact = new APIUsageResult.DependencyImpact(
                    dependency.dependencyKey(), "1", "2", "", "", "pom.xml",
                    "compile", "compile", "jar", "jar", false,
                    APIUsageResult.Classification.POTENTIAL_IMPACT, "", List.of(
                            new APIUsageResult.UsageFinding(firstChange, true, List.of(location)),
                            new APIUsageResult.UsageFinding(secondChange, true, List.of(location))));

            SmartDepTestRunner.printImpactReport(changes,
                    new APIUsageResult("project", List.of(impact)));
        } finally {
            System.setOut(previousOutput);
            if (previousLimit == null) {
                System.clearProperty("analysis.console.preview.limit");
            } else {
                System.setProperty("analysis.console.preview.limit", previousLimit);
            }
        }

        String report = output.toString(StandardCharsets.UTF_8);
        assertTrue(report.contains("Unique application methods with API-use evidence"));
        assertTrue(report.contains("Application API-reference records"));
        assertTrue(report.contains("Unique application methods with impact evidence"));
        assertTrue(report.contains("| Unique application methods with impact evidence | 1 |"));
        assertTrue(report.contains("API-impact records"));
        assertTrue(report.contains("1 additional API-impact records"));
        assertTrue(!report.contains("more impacted method"));
    }

    private static ApiChange change(String methodName) {
        return new ApiChange(ApiChange.Kind.METHOD_REMOVED, "org/example/Service", methodName,
                "", "", "()V", "", true);
    }
}