package smartdeptest.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import smartdeptest.analysis.APIUsageResult.Classification;
import smartdeptest.analysis.APIUsageResult.DependencyImpact;
import smartdeptest.analysis.APIUsageResult.UsageFinding;
import smartdeptest.analysis.APIUsageResult.UsageLocation;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class APIUsageAnalyzer {
    private final ApplicationModuleScanner moduleScanner = new ApplicationModuleScanner();
    private final MavenModuleClasspathResolver classpathResolver;

    public APIUsageAnalyzer() {
        this(new MavenModuleClasspathResolver());
    }

    APIUsageAnalyzer(MavenModuleClasspathResolver classpathResolver) {
        this.classpathResolver = classpathResolver;
    }

    public APIUsageResult analyze(APIChangeResult apiChanges, Path projectDirectory) {
        List<DependencyApiResult> analyzable = apiChanges.dependencies().stream()
                .filter(result -> result.status() == DependencyApiResult.Status.ANALYZED)
                .filter(result -> result.changes().stream().anyMatch(ApiChange::potentiallyIncompatible))
                .toList();
        if (analyzable.isEmpty()) {
            return new APIUsageResult(apiChanges.projectPath(), apiChanges.dependencies().stream()
                    .map(result -> impactWithoutSourceScan(result,
                            result.status() == DependencyApiResult.Status.ANALYZED
                                    ? Classification.NO_IDENTIFIED_IMPACT : Classification.ANALYSIS_UNAVAILABLE))
                    .toList());
        }

        try {
            List<ApplicationModule> modules = moduleScanner.discover(projectDirectory);
            if (modules.isEmpty()) throw new IOException("No Java files were found under standard src/main/java roots.");
                UsageIndex usageIndex = indexUsages(apiChanges, analyzable, modules, projectDirectory);
            List<DependencyImpact> impacts = apiChanges.dependencies().stream()
                    .map(result -> buildImpact(result, usageIndex)).toList();
            return new APIUsageResult(apiChanges.projectPath(), impacts);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new APIUsageResult(apiChanges.projectPath(), apiChanges.dependencies().stream()
                    .map(result -> unavailableImpact(result, message)).toList());
        }
    }

    private UsageIndex indexUsages(APIChangeResult apiChanges,
                                   List<DependencyApiResult> analyzable,
                                   List<ApplicationModule> modules,
                                   Path projectDirectory) throws IOException {
        Map<DependencyApiResult, Path> oldJars = new LinkedHashMap<>();
        for (DependencyApiResult dependency : analyzable) {
            if (dependency.oldArtifactPath().isBlank() || dependency.newArtifactPath().isBlank()) {
                throw new IOException("Resolved dependency JAR paths are unavailable for "
                        + dependency.dependencyKey() + ".");
            }
            oldJars.put(dependency, Path.of(dependency.oldArtifactPath()));
        }
        Map<String, List<UsageLocation>> index = new HashMap<>();
        List<String> incompleteModules = new ArrayList<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("A JDK with the Java compiler is required for source analysis.");
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        for (ApplicationModule module : modules) {
            List<Path> classpath = new ArrayList<>(classpathResolver.resolve(module));
            Map<String, List<String>> usageOwners = new HashMap<>();
            for (Map.Entry<DependencyApiResult, Path> entry : oldJars.entrySet()) {
            DependencyApiResult dependency = entry.getKey();
            Path currentJarSuffix = MavenArtifactResolver.artifactPathSuffix(dependency.groupId(),
                    dependency.artifactId(), dependency.newVersion(), dependency.newClassifier());
            Path oldJar = entry.getValue().toAbsolutePath().normalize();
            boolean includesUpdatedArtifact = classpath.stream().map(path -> path.toAbsolutePath().normalize())
                .anyMatch(path -> path.endsWith(currentJarSuffix) || path.equals(oldJar));
            if (!includesUpdatedArtifact) continue;
            classpath.removeIf(path -> path.toAbsolutePath().normalize().endsWith(currentJarSuffix));
            if (!classpath.stream().map(path -> path.toAbsolutePath().normalize()).anyMatch(path -> path.equals(oldJar))) {
                classpath.add(oldJar);
            }
            for (ApiChange change : dependency.changes()) {
                if (!change.potentiallyIncompatible()) continue;
                String key = lookupKey(change);
                if (!key.isBlank()) {
                usageOwners.computeIfAbsent(key, ignored -> new ArrayList<>()).add(dependency.dependencyKey());
                }
            }
            }
            if (usageOwners.isEmpty()) continue;
            modules.stream().map(other -> other.moduleDirectory().resolve("target/classes"))
                .filter(Files::isDirectory).map(path -> path.toAbsolutePath().normalize())
                .forEach(classpath::add);
            analyzeModule(compiler, module, classpath, projectRoot,
                modules.stream().map(ApplicationModule::sourceDirectory).toList(),
                usageOwners, index, incompleteModules);
        }
        return new UsageIndex(index, incompleteModules);
    }

    private static void analyzeModule(JavaCompiler compiler, ApplicationModule module, List<Path> classpath,
                                      Path projectRoot, List<Path> sourceRoots,
                                      Map<String, List<String>> usageOwners,
                                      Map<String, List<UsageLocation>> index,
                                      List<String> incompleteModules) throws IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> sourceFiles = fileManager.getJavaFileObjectsFromPaths(module.javaFiles());
                List<String> options = List.of("-proc:none", "-classpath", classpath.stream()
                        .distinct().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator)),
                    "-sourcepath", sourceRoots.stream().map(Path::toString)
                        .collect(Collectors.joining(java.io.File.pathSeparator)));
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, options, null, sourceFiles);
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            task.analyze();
            List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR).toList();
            if (!errors.isEmpty()) {
                Diagnostic<? extends JavaFileObject> first = errors.get(0);
                incompleteModules.add(module.moduleDirectory() + ": " + first.getMessage(null));
            }
            Trees trees = Trees.instance(task);
            Types types = task.getTypes();
            for (CompilationUnitTree unit : units) {
                new UsageScanner(trees, types, unit, projectRoot, usageOwners, index).scan(unit, null);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Unable to analyze Java source in " + module.moduleDirectory()
                    + ": " + exception.getMessage(), exception);
        }
    }

    private static DependencyImpact buildImpact(DependencyApiResult dependency, UsageIndex usageIndex) {
        List<UsageFinding> findings = new ArrayList<>();
        boolean usedIncompatibleApi = false;
        for (ApiChange change : dependency.changes()) {
                List<UsageLocation> locations = change.potentiallyIncompatible()
                    ? usageIndex.locations().getOrDefault(dependency.dependencyKey() + "||" + lookupKey(change), List.of())
                    : List.of();
            findings.add(new UsageFinding(change, !locations.isEmpty(), locations));
            if (!locations.isEmpty()) usedIncompatibleApi = true;
        }
        Classification classification = dependency.status() == DependencyApiResult.Status.UNAVAILABLE
                ? Classification.ANALYSIS_UNAVAILABLE
            : usedIncompatibleApi ? Classification.POTENTIAL_IMPACT
            : usageIndex.incompleteModules().isEmpty() ? Classification.NO_IDENTIFIED_IMPACT
            : Classification.ANALYSIS_UNAVAILABLE;
        String message = dependency.message();
        if (!usageIndex.incompleteModules().isEmpty()) {
            String incompleteMessage = "Some modules could not be fully resolved: "
                + String.join("; ", usageIndex.incompleteModules());
            message = message.isBlank() ? incompleteMessage : message + " " + incompleteMessage;
        }
        return new DependencyImpact(dependency.dependencyKey(), dependency.oldVersion(), dependency.newVersion(),
            classification, message, findings);
    }

    private static DependencyImpact impactWithoutSourceScan(DependencyApiResult dependency, Classification classification) {
        List<UsageFinding> findings = dependency.changes().stream()
                .map(change -> new UsageFinding(change, false, List.of())).toList();
        return new DependencyImpact(dependency.dependencyKey(), dependency.oldVersion(), dependency.newVersion(),
                classification, dependency.message(), findings);
    }

    private static DependencyImpact unavailableImpact(DependencyApiResult dependency, String message) {
        return new DependencyImpact(dependency.dependencyKey(), dependency.oldVersion(), dependency.newVersion(),
                Classification.ANALYSIS_UNAVAILABLE, message, List.of());
    }

    private static String lookupKey(ApiChange change) {
        return switch (change.kind()) {
            case CLASS_REMOVED -> "C:" + change.className();
            case CLASS_MODIFIED -> "C:" + change.className();
            case METHOD_ADDED -> "I:" + change.className();
            case METHOD_REMOVED, METHOD_MODIFIED -> methodKey(change);
            case FIELD_REMOVED, FIELD_MODIFIED -> fieldKey(change);
            default -> "";
        };
    }

    private static String methodKey(ApiChange change) {
        return "M:" + change.className() + ":" + change.memberName() + "("
                + String.join(",", change.oldParameterTypes()) + ")";
    }

    private static String fieldKey(ApiChange change) {
        return "F:" + change.className() + ":" + change.memberName();
    }

    private record UsageIndex(Map<String, List<UsageLocation>> locations, List<String> incompleteModules) {
        private UsageIndex {
            locations = Map.copyOf(locations);
            incompleteModules = List.copyOf(incompleteModules);
        }
    }

    private static final class UsageScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Types types;
        private final CompilationUnitTree unit;
        private final Path projectRoot;
        private final Map<String, List<String>> usageOwners;
        private final Map<String, List<UsageLocation>> index;

        private UsageScanner(Trees trees, Types types, CompilationUnitTree unit, Path projectRoot,
                     Map<String, List<String>> usageOwners,
                             Map<String, List<UsageLocation>> index) {
            this.trees = trees;
            this.types = types;
            this.unit = unit;
            this.projectRoot = projectRoot;
            this.usageOwners = usageOwners;
            this.index = index;
        }

        @Override
        public Void visitImport(ImportTree node, Void unused) {
            return null;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            recordExecutable(getCurrentPath());
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
            recordExecutable(getCurrentPath());
            return super.visitNewClass(node, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void unused) {
            recordExecutable(getCurrentPath());
            return super.visitMemberReference(node, unused);
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            TreePath path = getCurrentPath();
            Element element = trees.getElement(path);
            if (element instanceof TypeElement type) {
                for (String contractKey : usageOwners.keySet()) {
                    if (contractKey.startsWith("I:") && implementsOrExtends(type,
                            contractKey.substring(2), new java.util.HashSet<>())) {
                        addLocations(contractKey, path);
                    }
                }
            }
            return super.visitClass(node, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            recordTypeOrField(getCurrentPath());
            return super.visitIdentifier(node, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            recordTypeOrField(getCurrentPath());
            return super.visitMemberSelect(node, unused);
        }

        private void recordExecutable(TreePath path) {
            Element element = trees.getElement(path);
            if (!(element instanceof ExecutableElement executable)) return;
            Element owner = executable.getEnclosingElement();
            if (!(owner instanceof TypeElement type)) return;
            String name = executable.getKind() == ElementKind.CONSTRUCTOR
                    ? "<init>" : executable.getSimpleName().toString();
            List<String> parameters = executable.getParameters().stream()
                    .map(parameter -> types.erasure(parameter.asType()).toString()).toList();
            String key = "M:" + type.getQualifiedName() + ":" + name + "("
                    + String.join(",", parameters) + ")";
            addLocations(key, path);
        }

        private void recordTypeOrField(TreePath path) {
            Element element = trees.getElement(path);
            if (element instanceof TypeElement type) {
                String key = "C:" + type.getQualifiedName();
                addLocations(key, path);
            } else if (element instanceof VariableElement variable
                    && (variable.getKind() == ElementKind.FIELD || variable.getKind() == ElementKind.ENUM_CONSTANT)
                    && variable.getEnclosingElement() instanceof TypeElement owner) {
                String key = "F:" + owner.getQualifiedName() + ":" + variable.getSimpleName();
                addLocations(key, path);
            }
        }

        private void addLocations(String symbolKey, TreePath path) {
            for (String dependencyKey : usageOwners.getOrDefault(symbolKey, List.of())) {
                addLocation(dependencyKey + "||" + symbolKey, path);
            }
        }

        private boolean implementsOrExtends(TypeElement type, String target, Set<String> visited) {
            String name = type.getQualifiedName().toString();
            if (!visited.add(name)) return false;
            for (TypeMirror parent : types.directSupertypes(type.asType())) {
                Element parentElement = types.asElement(types.erasure(parent));
                if (parentElement instanceof TypeElement parentType) {
                    if (parentType.getQualifiedName().contentEquals(target)
                            || implementsOrExtends(parentType, target, visited)) return true;
                }
            }
            return false;
        }

        private void addLocation(String key, TreePath path) {
            Path source = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
            String sourcePath = projectRoot.relativize(source).toString().replace('\\', '/');
            String className = "";
            String methodName = "<initializer>";
            for (TreePath current = path; current != null; current = current.getParentPath()) {
                if (current.getLeaf() instanceof MethodTree method && methodName.equals("<initializer>")) {
                    methodName = method.getName().toString();
                }
                if (current.getLeaf() instanceof ClassTree) {
                    Element element = trees.getElement(current);
                    if (element instanceof TypeElement type && className.isBlank()) {
                        className = type.getQualifiedName().toString();
                    }
                }
            }
            long line = unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit, path.getLeaf()));
            UsageLocation location = new UsageLocation(sourcePath, className, methodName, line);
            List<UsageLocation> locations = index.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (!locations.contains(location)) locations.add(location);
        }
    }
}