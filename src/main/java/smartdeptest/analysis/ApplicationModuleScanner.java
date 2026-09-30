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
        Map<Path, Path> sourceRoots = new LinkedHashMap<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(projectRoot) && isIgnored(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path parent = directory.getParent();
                if (parent != null && parent.getFileName().toString().equals("main")
                        && parent.getParent() != null && parent.getParent().getFileName().toString().equals("src")
                        && directory.getFileName().toString().equals("java")) {
                    Path moduleDirectory = parent.getParent().getParent();
                    Path pom = findPom(moduleDirectory, projectRoot);
                    if (pom != null) sourceRoots.put(directory, pom);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<ApplicationModule> modules = new ArrayList<>();
        for (Map.Entry<Path, Path> sourceRoot : sourceRoots.entrySet()) {
            List<Path> javaFiles;
            try (var files = Files.walk(sourceRoot.getKey())) {
                javaFiles = files.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".java"))
                        .sorted().toList();
            }
            if (javaFiles.isEmpty()) continue;
            modules.add(new ApplicationModule(sourceRoot.getValue().getParent(), sourceRoot.getValue(),
                    sourceRoot.getKey(), javaFiles));
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

    private static boolean isIgnored(String name) {
        return name.equals(".git") || name.equals("target") || name.equals("node_modules");
    }
}