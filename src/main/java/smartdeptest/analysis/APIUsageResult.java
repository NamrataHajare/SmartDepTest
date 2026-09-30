package smartdeptest.analysis;

import java.util.List;

public record APIUsageResult(String projectPath, List<DependencyImpact> dependencies) {
    public enum Classification { POTENTIAL_IMPACT, NO_IDENTIFIED_IMPACT, ANALYSIS_UNAVAILABLE }

    public APIUsageResult {
        dependencies = List.copyOf(dependencies);
    }

    public record DependencyImpact(String dependencyKey, String oldVersion, String newVersion,
                                   Classification classification, String message,
                                   List<UsageFinding> findings) {
        public DependencyImpact {
            findings = List.copyOf(findings);
            message = message == null ? "" : message;
        }
    }

    public record UsageFinding(ApiChange change, boolean used, List<UsageLocation> locations) {
        public UsageFinding {
            locations = List.copyOf(locations);
        }
    }

    public record UsageLocation(String sourcePath, String className,
                                String methodName, long line) {}
}