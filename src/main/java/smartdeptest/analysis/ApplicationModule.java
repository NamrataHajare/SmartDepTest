package smartdeptest.analysis;

import java.nio.file.Path;
import java.util.List;

record ApplicationModule(Path moduleDirectory, Path pomFile, Path sourceDirectory,
                         List<Path> javaFiles) {
    ApplicationModule {
        javaFiles = List.copyOf(javaFiles);
    }
}