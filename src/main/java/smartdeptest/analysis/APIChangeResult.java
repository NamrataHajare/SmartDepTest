package smartdeptest.analysis;

import java.util.List;

public record APIChangeResult(String projectPath, List<DependencyApiResult> dependencies) {
    public APIChangeResult {
        dependencies = List.copyOf(dependencies);
    }
}