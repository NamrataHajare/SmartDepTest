package smartdeptest.analysis;

import java.util.List;

public record APIUsageResult(String projectPath, List<DependencyImpact> dependencies) {
    public enum Classification { POTENTIAL_IMPACT, NO_IDENTIFIED_IMPACT, ANALYSIS_UNAVAILABLE }

    public APIUsageResult {
        dependencies = List.copyOf(dependencies);
    }

    public record DependencyImpact(String dependencyKey, String oldVersion, String newVersion,
                                   String oldClassifier, String newClassifier, String pomPath,
                                   String oldScope, String newScope, String oldType, String newType,
                                   boolean dependencyManagement,
                                   Classification classification, String message,
                                   List<UsageFinding> findings) {
        public DependencyImpact(String dependencyKey, String oldVersion, String newVersion,
                                Classification classification, String message,
                                List<UsageFinding> findings) {
            this(dependencyKey, oldVersion, newVersion, "", "", "", "", "", "", "", false,
                    classification, message, findings);
        }

        public DependencyImpact {
            findings = List.copyOf(findings);
            message = message == null ? "" : message;
            oldClassifier = oldClassifier == null ? "" : oldClassifier;
            newClassifier = newClassifier == null ? "" : newClassifier;
            pomPath = pomPath == null ? "" : pomPath;
            oldScope = oldScope == null ? "" : oldScope;
            newScope = newScope == null ? "" : newScope;
            oldType = oldType == null ? "" : oldType;
            newType = newType == null ? "" : newType;
        }
    }

    public record UsageFinding(ApiChange change, boolean used, List<UsageLocation> locations) {
        public UsageFinding {
            locations = List.copyOf(locations);
        }
    }

    public record UsageLocation(String className, String methodName,
                                String methodDescriptor, String instructionType) {}
}