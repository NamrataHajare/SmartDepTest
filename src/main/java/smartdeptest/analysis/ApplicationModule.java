package smartdeptest.analysis;

import java.nio.file.Path;

record ApplicationModule(Path projectDirectory, Path moduleDirectory, Path pomFile, Path classesDirectory) {}