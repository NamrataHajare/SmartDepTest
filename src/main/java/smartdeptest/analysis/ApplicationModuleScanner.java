package smartdeptest.analysis;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ApplicationModuleScanner {
    List<ApplicationModule> discover(Path projectDirectory) throws IOException {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Map<Path, Path> modulePoms = new LinkedHashMap<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(projectRoot) && (directory.getFileName().toString().equals(".git")
                        || directory.getFileName().toString().equals("node_modules"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.isDirectory(directory.resolve("src/main/java"))
                        || Files.isDirectory(directory.resolve("target/classes"))) {
                    Path pom = findPom(directory, projectRoot);
                    if (pom != null) modulePoms.put(pom.getParent(), pom);
                }
                if (directory.getFileName().toString().equals("target")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<ApplicationModule> modules = new ArrayList<>();
        for (Map.Entry<Path, Path> module : modulePoms.entrySet()) {
            modules.add(new ApplicationModule(projectRoot, module.getKey(), module.getValue(),
                    module.getKey().resolve("target/classes")));
        }
        modules.sort(Comparator.comparing(module -> module.moduleDirectory().toString()));
        return List.copyOf(modules);
    }

    private static Path findPom(Path moduleDirectory, Path projectRoot) {
        for (Path directory = moduleDirectory; directory != null && directory.startsWith(projectRoot);
             directory = directory.getParent()) {
            Path pom = directory.resolve("pom.xml");
            if (Files.isRegularFile(pom)) return pom;
        }
        return null;
    }
}