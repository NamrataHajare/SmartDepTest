package smartdeptest.analysis;

import java.nio.file.Path;
import java.util.List;

record ApplicationModule(Path projectDirectory, Path moduleDirectory, Path pomFile,
						 List<Path> classesDirectories, boolean hasMainSources) {
	ApplicationModule(Path projectDirectory, Path moduleDirectory, Path pomFile, Path classesDirectory) {
		this(projectDirectory, moduleDirectory, pomFile, List.of(classesDirectory), true);
	}

	ApplicationModule {
		classesDirectories = List.copyOf(classesDirectories);
	}
}