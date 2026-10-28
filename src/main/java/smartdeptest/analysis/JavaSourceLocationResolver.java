package smartdeptest.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import smartdeptest.graph.DependencyGraphResult;
import smartdeptest.graph.GraphNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JavaSourceLocationResolver {

    public List<Map<String, Object>> applicationClassLocations(
            Path projectDirectory, DependencyGraphResult result) throws IOException {
        Path root = projectDirectory.toAbsolutePath().normalize();
        List<Map<String, Object>> locations = new ArrayList<>();

        for (String classId : new LinkedHashSet<>(result.affectedNodes())) {
            GraphNode node = result.graph().getNode(classId);
            if (node == null || node.type() != GraphNode.Type.CLASS) {
                continue;
            }

            String className = stringValue(node.metadata().get("className"));
            Path module = projectRelativeModule(root, node.metadata().get("modulePath"));
            ClassLocation classLocation = findClassLocation(
                    root, module, className.replace('.', '/') + ".class", null, null, false);
            Path source = findSourceFile(root, module, className, classLocation.sourceFile(), false);

            Map<String, Object> location = new LinkedHashMap<>();
            location.put("classId", classId);
            location.put("className", className);
            location.put("sourcePath", relativeSourcePath(root, source));
            location.put("lineNumber", null);
            location.put("locationStatus", locationStatus(source, null));
            locations.add(location);
        }
        return List.copyOf(locations);
    }

    public List<Map<String, Object>> applicationMethodLocations(
            Path projectDirectory, DependencyGraphResult result) throws IOException {
        Path root = projectDirectory.toAbsolutePath().normalize();
        List<Map<String, Object>> locations = new ArrayList<>();

        for (String methodId : new LinkedHashSet<>(result.allAffectedMethods())) {
            GraphNode node = result.graph().getNode(methodId);
            if (node == null || node.type() != GraphNode.Type.METHOD) {
                continue;
            }

            Map<String, Object> metadata = node.metadata();
            String className = stringValue(metadata.get("className"));
            String methodName = stringValue(metadata.get("methodName"));
            String descriptor = stringValue(metadata.get("descriptor"));
            Path module = projectRelativeModule(root, metadata.get("modulePath"));
            ClassLocation classLocation = findClassLocation(
                    root, module, className.replace('.', '/') + ".class",
                    methodName, descriptor, false);
            Path source = findSourceFile(root, module, className, classLocation.sourceFile(), false);

            Map<String, Object> location = new LinkedHashMap<>();
            location.put("methodId", methodId);
            location.put("className", className);
            location.put("methodName", methodName);
            location.put("descriptor", descriptor);
            location.put("sourcePath", relativeSourcePath(root, source));
            location.put("lineNumber", classLocation.lineNumber());
            location.put("locationStatus", locationStatus(source, classLocation.lineNumber()));
            locations.add(location);
        }
        return List.copyOf(locations);
    }

    public List<Map<String, Object>> testCaseLocations(
            Path projectDirectory, DependencyGraphResult result) throws IOException {
        Path root = projectDirectory.toAbsolutePath().normalize();
        Set<String> testIds = new LinkedHashSet<>();
        result.methodTestMapping().forEach(mapping ->
                testIds.add(mapping.testClass() + "#" + mapping.testMethod()));
        result.selectedTests().forEach(testIds::add);
        result.testCoverageStatuses().forEach(test ->
                testIds.add(test.testClass() + "#" + test.testMethod()));
        result.groupedSelectedTests().forEach(group -> testIds.addAll(group.selectedTests()));

        List<Map<String, Object>> locations = new ArrayList<>();
        for (String testId : testIds) {
            int separator = testId.lastIndexOf('#');
            if (separator <= 0 || separator == testId.length() - 1) {
                continue;
            }

            String testClass = testId.substring(0, separator);
            String methodName = testId.substring(separator + 1);
            int moduleSeparator = testClass.indexOf("::");
            String moduleName = moduleSeparator < 0 ? "" : testClass.substring(0, moduleSeparator);
            if (moduleSeparator >= 0) {
                testClass = testClass.substring(moduleSeparator + 2);
            }
            Path module = moduleName.isBlank()
                    ? null : safeResolve(root, moduleName);
            ClassLocation classLocation = findClassLocation(
                    root, module, testClass.replace('.', '/') + ".class", methodName, null, true);
            if (module == null) {
                module = classLocation.module();
            }
            Path source = findSourceFile(root, module, testClass, classLocation.sourceFile(), true);

            Map<String, Object> location = new LinkedHashMap<>();
            location.put("testId", testId);
            location.put("testClass", testClass);
            location.put("testMethod", methodName);
            location.put("sourcePath", relativeSourcePath(root, source));
            location.put("lineNumber", classLocation.lineNumber());
            location.put("locationStatus", locationStatus(source, classLocation.lineNumber()));
            locations.add(location);
        }
        return List.copyOf(locations);
    }

    private static ClassLocation findClassLocation(
            Path projectRoot, Path preferredModule, String classRelativePath, String methodName,
            String descriptor, boolean testClass) throws IOException {
        List<Path> modules = preferredModule == null
                ? discoverModules(projectRoot)
                : List.of(preferredModule);
        List<ClassLocation> found = new ArrayList<>();
        for (Path module : modules) {
            ClassLocation location = findClassLocationInModule(
                    module, classRelativePath, methodName, descriptor, testClass);
            if (location != null && location.classFile() != null) {
                found.add(location);
            }
        }
        if (preferredModule == null && found.size() > 1) {
            return new ClassLocation(null, null, null, null);
        }
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return new ClassLocation(preferredModule, null, null, null);
    }

    private static ClassLocation findClassLocationInModule(
            Path module, String classRelativePath, String methodName,
            String descriptor, boolean testClass) throws IOException {
        Path target = module.resolve("target");
        if (!Files.isDirectory(target)) {
            return null;
        }

        try (var files = Files.walk(target)) {
            for (Path classFile : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().replace('\\', '/')
                            .endsWith(classRelativePath.replace('\\', '/')))
                    .filter(path -> isTestOutput(target, path) == testClass)
                    .toList()) {
                ClassLocation location = readClassLocation(
                        classFile, module, methodName, descriptor);
                if (location.classFile() != null) {
                    return location;
                }
            }
        }
        return null;
    }

    private static List<Path> discoverModules(Path root) throws IOException {
        List<Path> modules = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(root)) {
                    Path name = directory.getFileName();
                    if (name != null && (name.toString().equals(".git")
                            || name.toString().equals("node_modules")
                            || name.toString().equals("target"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.getFileName().toString().equals("pom.xml")) {
                    modules.add(file.getParent());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        modules.sort(Comparator.comparing(Path::toString));
        return modules;
    }

    private static boolean isTestOutput(Path target, Path classFile) {
        String relative = target.relativize(classFile).toString().replace('\\', '/').toLowerCase();
        return relative.split("/").length > 1
                && java.util.Arrays.stream(relative.split("/"))
                        .anyMatch(segment -> segment.contains("test"));
    }

    private static ClassLocation readClassLocation(
            Path classFile, Path module, String methodName, String descriptor) throws IOException {
        String[] sourceFile = {null};
        Integer[] lineNumber = {null};
        boolean[] foundMethod = {false};
        try (InputStream input = Files.newInputStream(classFile)) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitSource(String source, String debug) {
                    sourceFile[0] = source;
                }

                @Override
                public void visit(
                        int version, int access, String name, String signature,
                        String superName, String[] interfaces) {
                    if (methodName == null) {
                        foundMethod[0] = true;
                    }
                }

                @Override
                public MethodVisitor visitMethod(
                        int access, String name, String methodDescriptor,
                        String signature, String[] exceptions) {
                    if (methodName == null) {
                        foundMethod[0] = true;
                        return null;
                    }
                    if (!name.equals(methodName)
                            || (descriptor != null && !descriptor.equals(methodDescriptor))) {
                        return null;
                    }
                    foundMethod[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitLineNumber(int line, org.objectweb.asm.Label start) {
                            if (line > 0 && (lineNumber[0] == null || line < lineNumber[0])) {
                                lineNumber[0] = line;
                            }
                        }
                    };
                }
            }, 0);
        }
        return foundMethod[0]
                ? new ClassLocation(module, sourceFile[0], lineNumber[0], classFile)
                : new ClassLocation(module, null, null, null);
    }

    private static Path findSourceFile(
            Path root, Path module, String className, String sourceFile, boolean testSource)
            throws IOException {
        String resolvedFileName = sourceFile == null || sourceFile.isBlank()
                ? className.substring(className.lastIndexOf('.') + 1).replaceFirst("\\$.*$", "") + ".java"
                : sourceFile;
        int innerClass = resolvedFileName.indexOf('$');
        if (innerClass >= 0) {
            resolvedFileName = resolvedFileName.substring(0, innerClass) + ".java";
        }
        final String fileName = resolvedFileName;

        String packagePath = className.contains(".")
                ? className.substring(0, className.lastIndexOf('.')).replace('.', '/') : "";
        Path expectedSuffix = packagePath.isBlank()
                ? Path.of(fileName) : Path.of(packagePath).resolve(fileName);
        List<Path> modules = module == null ? discoverModules(root) : List.of(module);
        List<Path> matches = new ArrayList<>();
        for (Path sourceModule : modules) {
            Path sources = sourceModule.resolve("src");
            if (!Files.isDirectory(sources)) {
                continue;
            }
            try (var files = Files.walk(sources)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals(fileName))
                        .filter(path -> path.normalize().endsWith(expectedSuffix))
                        .filter(path -> path.toAbsolutePath().normalize().startsWith(root))
                        .filter(path -> testSource
                                == path.toString().replace('\\', '/').contains("/src/test/"))
                        .forEach(matches::add);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static Path projectRelativeModule(Path root, Object value) {
        if (!(value instanceof String modulePath) || modulePath.isBlank()) {
            return root;
        }
        Path module = safeResolve(root, modulePath);
        return module != null && module.startsWith(root) ? module : root;
    }

    private static Path safeResolve(Path root, String value) {
        try {
            Path supplied = Path.of(value);
            Path resolved = supplied.isAbsolute() ? supplied.normalize() : root.resolve(supplied).normalize();
            return resolved.startsWith(root) ? resolved : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String relativeSourcePath(Path root, Path source) {
        return source == null || !source.toAbsolutePath().normalize().startsWith(root)
                ? null : root.relativize(source.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
    }

    private static String locationStatus(Path source, Integer lineNumber) {
        if (source == null) {
            return "UNAVAILABLE";
        }
        return lineNumber == null ? "FILE_ONLY" : "AVAILABLE";
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private record ClassLocation(
            Path module, String sourceFile, Integer lineNumber, Path classFile) {
    }
}
