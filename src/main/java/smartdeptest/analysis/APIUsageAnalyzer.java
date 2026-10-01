package smartdeptest.analysis;

import java.nio.file.Path;

public final class APIUsageAnalyzer {
    private final MavenModuleClasspathResolver classpathResolver;

    public APIUsageAnalyzer() {
        this(new MavenModuleClasspathResolver());
    }

    APIUsageAnalyzer(MavenModuleClasspathResolver classpathResolver) {
        this.classpathResolver = classpathResolver;
    }

    public APIUsageResult analyze(APIChangeResult apiChanges, Path projectDirectory) {
        return new BytecodeAPIUsageAnalyzer(classpathResolver).analyze(apiChanges, projectDirectory);
    }
}