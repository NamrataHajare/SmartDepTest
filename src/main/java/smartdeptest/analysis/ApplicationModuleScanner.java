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
    private final MavenModuleClasspathResolver outputResolver;

    ApplicationModuleScanner(MavenModuleClasspathResolver outputResolver) {
        this.outputResolver = outputResolver;
    }

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
                if (directory.getFileName().toString().equals("target")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path pom = directory.resolve("pom.xml");
                if (Files.isRegularFile(pom)) modulePoms.put(directory, pom);
                return FileVisitResult.CONTINUE;
            }
        });

        List<ApplicationModule> modules = new ArrayList<>();
        for (Map.Entry<Path, Path> module : modulePoms.entrySet()) {
            boolean hasMainSources = Files.isDirectory(module.getKey().resolve("src/main/java"))
                    || Files.isDirectory(module.getKey().resolve("src/main/kotlin"))
                    || Files.isDirectory(module.getKey().resolve("src/main/scala"));
            boolean hasConfiguredSources = hasSourceDirectoryConfiguration(module.getKey(), projectRoot);
            boolean hasApplicationSources = hasMainSources || hasConfiguredSources;
            boolean hasDefaultOutput = Files.isDirectory(module.getKey().resolve("target/classes"));
            if (!hasApplicationSources && !hasDefaultOutput
                    && !hasBuildDiscoveryConfiguration(module.getKey(), projectRoot)) continue;
            ApplicationModule unresolved = new ApplicationModule(projectRoot, module.getKey(), module.getValue(),
                    List.of(), hasApplicationSources);
            List<Path> classesDirectories;
            try {
                classesDirectories = outputResolver.resolveOutputDirectories(unresolved);
            } catch (IOException exception) {
                if (hasApplicationSources || hasDefaultOutput) {
                    throw exception;
                }
                continue;
            }
            if (hasApplicationSources || classesDirectories.stream().anyMatch(Files::isDirectory)) {
                modules.add(new ApplicationModule(projectRoot, module.getKey(), module.getValue(),
                        classesDirectories, hasApplicationSources));
            }
        }
        modules.sort(Comparator.comparing(module -> module.moduleDirectory().toString()));
        return List.copyOf(modules);
    }

    private static boolean hasBuildDiscoveryConfiguration(Path moduleDirectory, Path projectRoot) throws IOException {
        for (Path directory = moduleDirectory; directory != null && directory.startsWith(projectRoot);
             directory = directory.getParent()) {
            Path pom = directory.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) continue;
            String contents = Files.readString(pom);
            if (contents.contains("<outputDirectory") || contents.contains("<directory>")
                    || contents.contains("<sourceDirectory")) return true;
        }
        return false;
    }

    private static boolean hasSourceDirectoryConfiguration(Path moduleDirectory, Path projectRoot)
            throws IOException {
        for (Path directory = moduleDirectory; directory != null && directory.startsWith(projectRoot);
             directory = directory.getParent()) {
            Path pom = directory.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.readString(pom).contains("<sourceDirectory")) return true;
        }
        return false;
    }
}