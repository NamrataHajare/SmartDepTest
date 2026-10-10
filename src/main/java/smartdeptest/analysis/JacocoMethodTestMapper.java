package smartdeptest.analysis;

import smartdeptest.graph.DependencyGraph;
import smartdeptest.graph.DependencyGraphResult;
import smartdeptest.graph.GraphEdge;
import smartdeptest.graph.GraphNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JacocoMethodTestMapper {

    private static final String TEST_SELECTION_UNAVAILABLE =
            "JaCoCo XML reports contain aggregate method coverage, not test-method attribution. "
                    + "No regression test is selected without per-test coverage evidence.";
    private static final String STATIC_SELECTION_NOTE =
            "Selection uses statically traceable test-bytecode call paths, not runtime coverage. "
                    + "JaCoCo execution data is not used.";

    public CoverageResult map(
            Path projectDirectory,
            List<String> affectedMethods) throws IOException {
        return map(projectDirectory, affectedMethods, List.of(), List.of());
    }

    public CoverageResult map(
            Path projectDirectory,
            DependencyGraphResult impactResult) throws IOException {

        if (impactResult == null) {
            throw new IOException(
                    "Current dependency/API impact result is required for test mapping.");
        }

        return map(
                projectDirectory,
                impactResult.allAffectedMethods(),
                impactResult.directlyImpactedMethods(),
                impactResult.indirectlyAffectedMethods(),
                impactResult.graph());
    }

    public CoverageResult map(
            Path projectDirectory,
            List<String> affectedMethods,
            List<String> directlyImpactedMethods,
            List<String> indirectlyAffectedMethods) throws IOException {
        return map(
                projectDirectory,
                affectedMethods,
                directlyImpactedMethods,
                indirectlyAffectedMethods,
                null);
    }

    private CoverageResult map(
            Path projectDirectory,
            List<String> affectedMethods,
            List<String> directlyImpactedMethods,
            List<String> indirectlyAffectedMethods,
            DependencyGraph applicationGraph) throws IOException {

        if (projectDirectory == null || !Files.isDirectory(projectDirectory)) {
            throw new IOException(
                    "Project directory does not exist or is not a directory: "
                            + projectDirectory);
        }

        if (affectedMethods == null || affectedMethods.isEmpty()) {
            return new CoverageResult(
                    List.of(), List.of(), List.of(), List.of(), List.of(), "");
        }

        Path root = projectDirectory.toAbsolutePath().normalize();
        List<Path> modules = discoverModules(root);

        if (applicationGraph == null) {
            return legacyCoverageStatus(
                    modules,
                    affectedMethods,
                    directlyImpactedMethods,
                    indirectlyAffectedMethods);
        }

        List<String> failures = new ArrayList<>();
        String evidenceNote = STATIC_SELECTION_NOTE + " "
                + describeJaCoCoArtifacts(modules);

        List<Path> affectedModules = identifyAffectedApplicationModules(
                root, modules, affectedMethods, applicationGraph);

        Map<String, Set<String>> testsByAffectedMethod = new LinkedHashMap<>();
        for (String method : new LinkedHashSet<>(affectedMethods)) {
            testsByAffectedMethod.put(method, new LinkedHashSet<>());
        }

        mapMavenInvokerFixtures(
                root,
                modules,
                applicationGraph,
                new LinkedHashSet<>(affectedMethods),
                testsByAffectedMethod,
                failures);
        boolean fixtureTestsFound = testsByAffectedMethod.values()
                .stream().anyMatch(tests -> !tests.isEmpty());

        if (affectedModules.isEmpty()) {
            if (fixtureTestsFound) {
                return buildCoverageResult(
                        affectedMethods,
                        directlyImpactedMethods,
                        indirectlyAffectedMethods,
                        testsByAffectedMethod,
                        true,
                        failures,
                        evidenceNote);
            }

            String reason =
                    "Unable to identify Maven module(s) containing "
                            + "the affected application classes.";
            failures.add(reason);

            return new CoverageResult(
                    List.of(),
                    List.of(),
                    buildUnavailableGroups(
                            affectedMethods,
                            directlyImpactedMethods,
                            indirectlyAffectedMethods,
                            reason),
                    failures,
                    List.of(),
                    "");
        }

        List<Path> testModules = new ArrayList<>();
        Map<Path, List<Path>> testOutputDirectories = new LinkedHashMap<>();
        for (Path module : modules) {
            if (hasTestSourcesOrClasses(module)) {
                testModules.add(module);
                testOutputDirectories.put(module, testOutputDirectories(module));
            }
        }

        boolean testBytecodeReady = ensureTestBytecodeCurrent(
                root, testModules, testOutputDirectories, failures);

        Map<Path, Map<MethodKey, Set<MethodKey>>> testCallsByModule =
                new LinkedHashMap<>();
        Set<TestEntryPoint> testEntryPoints = new LinkedHashSet<>();

        if (testBytecodeReady) {
            for (Path moduleRoot : testModules) {
                Map<MethodKey, Set<MethodKey>> moduleCalls =
                        new LinkedHashMap<>();
                Set<TestEntryPoint> moduleEntryPoints = new LinkedHashSet<>();
                List<Path> existingOutputs = testOutputDirectories.get(moduleRoot)
                        .stream().filter(Files::isDirectory).toList();
                if (!existingOutputs.isEmpty()) {
                    readTestBytecode(
                            existingOutputs,
                            moduleRoot,
                            moduleCalls,
                            moduleEntryPoints);
                    testCallsByModule.put(moduleRoot, moduleCalls);
                    testEntryPoints.addAll(moduleEntryPoints);
                }
            }
        }

        if (testBytecodeReady && testEntryPoints.isEmpty() && !fixtureTestsFound) {
            String reason = "No supported test entry points were found in compiled test bytecode. "
                    + evidenceNote;
            failures.add(reason);
            return new CoverageResult(
                    List.of(),
                    List.of(),
                    buildUnavailableGroups(
                            affectedMethods,
                            directlyImpactedMethods,
                            indirectlyAffectedMethods,
                            reason),
                    failures,
                    List.of(),
                    reason);
        }

        Map<MethodKey, String> applicationMethods =
                applicationMethods(applicationGraph);
        Map<String, Set<String>> callsByApplicationMethod =
                applicationCalls(applicationGraph);
        Set<String> affectedSet = new LinkedHashSet<>(affectedMethods);
        Map<String, Set<Path>> modulesByTestClass = new LinkedHashMap<>();
        for (TestEntryPoint entryPoint : testEntryPoints) {
            modulesByTestClass.computeIfAbsent(
                    entryPoint.testClass(), ignored -> new LinkedHashSet<>())
                    .add(entryPoint.moduleRoot());
        }

        for (TestEntryPoint entryPoint : testEntryPoints) {
            String testClass = entryPoint.testClass().replace('/', '.');
            if (modulesByTestClass.getOrDefault(
                    entryPoint.testClass(), Set.of()).size() > 1) {
                String moduleName = root.relativize(entryPoint.moduleRoot())
                        .toString().replace('\\', '/');
                testClass = moduleName + "::" + testClass;
            }
            String testId = testClass
                    + "#" + entryPoint.method().name();

            Set<String> reachedApplicationMethods = new LinkedHashSet<>();

            traceTestMethod(
                    entryPoint.method(),
                    testCallsByModule.getOrDefault(
                            entryPoint.moduleRoot(), Map.of()),
                    applicationMethods,
                    callsByApplicationMethod,
                    new HashSet<>(),
                    reachedApplicationMethods);

            for (String affectedMethod : affectedSet) {
                if (reachedApplicationMethods.contains(affectedMethod)) {
                    testsByAffectedMethod
                            .computeIfAbsent(
                                    affectedMethod, ignored -> new LinkedHashSet<>())
                            .add(testId);
                }
            }
        }

        return buildCoverageResult(
                affectedMethods,
                directlyImpactedMethods,
                indirectlyAffectedMethods,
                testsByAffectedMethod,
                testBytecodeReady,
                failures,
                evidenceNote);
    }

    private static CoverageResult buildCoverageResult(
            List<String> affectedMethods,
            List<String> directlyImpactedMethods,
            List<String> indirectlyAffectedMethods,
            Map<String, Set<String>> testsByAffectedMethod,
            boolean testBytecodeReady,
            List<String> failures,
            String selectionNote) {

        List<DependencyGraphResult.MethodTestCoverage> mappings =
                new ArrayList<>();
        LinkedHashSet<String> selectedTests = new LinkedHashSet<>();
        List<DependencyGraphResult.AffectedMethodTestGroup> groups =
                new ArrayList<>();

        for (String method : new LinkedHashSet<>(affectedMethods)) {
            List<String> tests = testsByAffectedMethod
                    .getOrDefault(method, Set.of())
                    .stream().sorted().toList();

            for (String testId : tests) {
                int separator = testId.lastIndexOf('#');
                if (separator <= 0 || separator >= testId.length() - 1) {
                    continue;
                }

                mappings.add(new DependencyGraphResult.MethodTestCoverage(
                        method,
                        testId.substring(0, separator),
                        testId.substring(separator + 1)));
                selectedTests.add(testId);
            }

            String status = !testBytecodeReady
                    ? "NOT ANALYZED"
                    : tests.isEmpty() ? "NONE FOUND" : "SELECTED";

            String note = testBytecodeReady
                    ? tests.isEmpty()
                            ? "No statically traceable test call path was found. "
                                    + "This does not prove runtime non-coverage. "
                                    + selectionNote
                            : selectionNote
                    : "Test bytecode compilation failed; "
                            + "coverage attribution was not inferred.";

            groups.add(new DependencyGraphResult.AffectedMethodTestGroup(
                    method,
                    impactTypeFor(
                            method,
                            directlyImpactedMethods,
                            indirectlyAffectedMethods),
                    tests,
                    status,
                    note));
        }

        return new CoverageResult(
                mappings,
                new ArrayList<>(selectedTests),
                groups,
                failures,
                List.of(),
                selectionNote);
    }

    private static List<Path> identifyAffectedApplicationModules(
            Path root,
            List<Path> modules,
            List<String> affectedMethods,
            DependencyGraph applicationGraph) throws IOException {

        Set<Path> matchedModules = new LinkedHashSet<>();
        Set<String> affectedMethodIds = new LinkedHashSet<>(affectedMethods);

        for (GraphNode node : applicationGraph.getNodes()) {
            if (node.type() != GraphNode.Type.METHOD
                    || !affectedMethodIds.contains(node.id())) {
                continue;
            }

            Object value = node.metadata().get("modulePath");
            if (!(value instanceof String modulePath) || modulePath.isBlank()) {
                continue;
            }

            try {
                Path module = Path.of(modulePath).toAbsolutePath().normalize();
                if (module.startsWith(root)
                        && Files.isDirectory(module)
                        && Files.isRegularFile(module.resolve("pom.xml"))) {
                    matchedModules.add(module);
                }
            } catch (RuntimeException ignored) {
                // Invalid metadata must not prevent fallback discovery.
            }
        }

        Set<String> affectedOwners = new LinkedHashSet<>();
        for (String affectedMethod : affectedMethods) {
            String owner = extractOwnerClassName(affectedMethod);
            if (owner != null && !owner.isBlank()) {
                affectedOwners.add(owner.replace('.', '/'));
            }
        }

        if (affectedOwners.isEmpty()) {
            return matchedModules.stream().sorted().toList();
        }

        for (Path module : modules) {
            Path classes = module.resolve("target/classes");
            if (!Files.isDirectory(classes)) {
                continue;
            }

            for (String owner : affectedOwners) {
                if (Files.isRegularFile(classes.resolve(owner + ".class"))) {
                    matchedModules.add(module);
                    break;
                }
            }
        }

        if (matchedModules.isEmpty()) {
            for (Path module : modules) {
                Path mainJava = module.resolve("src/main/java");
                if (!Files.isDirectory(mainJava)) {
                    continue;
                }

                for (String owner : affectedOwners) {
                    if (Files.isRegularFile(mainJava.resolve(owner + ".java"))) {
                        matchedModules.add(module);
                        break;
                    }
                }
            }
        }

        if (matchedModules.isEmpty()) {
            for (Path module : modules) {
                for (String owner : affectedOwners) {
                    String relativeSourcePath = owner + ".java";
                    try (var paths = Files.walk(module)) {
                        boolean found = paths
                                .filter(Files::isRegularFile)
                                .anyMatch(path -> path.toString()
                                        .replace('\\', '/')
                                        .endsWith(relativeSourcePath));
                        if (found) {
                            matchedModules.add(module);
                            break;
                        }
                    } catch (IOException ignored) {
                        // Continue searching other modules.
                    }
                }
            }
        }

        return matchedModules.stream().sorted().toList();
    }

    private static String extractOwnerClassName(String affectedMethod) {
        if (affectedMethod == null || affectedMethod.isBlank()) {
            return null;
        }

        int descriptorStart = affectedMethod.indexOf('(');
        if (descriptorStart <= 0) {
            return null;
        }

        String methodPart = affectedMethod.substring(0, descriptorStart);
        int separator = methodPart.lastIndexOf('.');
        return separator <= 0 ? null : methodPart.substring(0, separator);
    }

    private static boolean ensureTestBytecodeCurrent(
            Path root,
            List<Path> testModules,
            Map<Path, List<Path>> testOutputDirectories,
            List<String> failures) throws IOException {

        List<Path> modulesNeedingCompilation = new ArrayList<>();

        for (Path module : testModules) {
            Path sourceDirectory = module.resolve("src/test");
            if (!Files.isDirectory(sourceDirectory)
                    || !hasJavaTestSources(sourceDirectory)) {
                continue;
            }

            List<Path> outputDirectories = testOutputDirectories.get(module);
            boolean needsCompilation = outputDirectories.stream()
                    .noneMatch(Files::isDirectory);

            if (!needsCompilation) {
                Path newestClass = null;
                for (Path outputDirectory : outputDirectories) {
                    if (!Files.isDirectory(outputDirectory)) {
                        continue;
                    }
                    try (var classes = Files.walk(outputDirectory)) {
                        Path candidate = classes
                                .filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().endsWith(".class"))
                                .max(JacocoMethodTestMapper::compareModifiedTime)
                                .orElse(null);
                        if (candidate != null && (newestClass == null
                                || compareModifiedTime(candidate, newestClass) > 0)) {
                            newestClass = candidate;
                        }
                    } catch (IOException exception) {
                        failures.add("Unable to inspect test compilation freshness in "
                                + module + ": " + exception.getMessage());
                        return false;
                    }
                }
                needsCompilation = newestClass == null
                        || hasSourceNewerThan(sourceDirectory, newestClass);
            }

            if (needsCompilation) {
                modulesNeedingCompilation.add(module);
            }
        }

        if (modulesNeedingCompilation.isEmpty()) {
            return true;
        }

        Path rootPom = root.resolve("pom.xml").toAbsolutePath().normalize();
        boolean rootIsIncluded = false;

        for (Path module : modulesNeedingCompilation) {
            Path modulePath = module.toAbsolutePath().normalize();

            if (modulePath.equals(root.toAbsolutePath().normalize())) {
                rootIsIncluded = true;
                continue;
            }

            String moduleSelector;
            try {
                moduleSelector = root.toAbsolutePath().normalize()
                        .relativize(modulePath).toString().replace('\\', '/');
            } catch (IllegalArgumentException exception) {
                failures.add("Unable to determine Maven module path for "
                        + module + ": " + exception.getMessage());
                return false;
            }

            if (moduleSelector.isBlank() || ".".equals(moduleSelector)) {
                rootIsIncluded = true;
                continue;
            }

            try {
                MavenCommandRunner.run(
                        root,
                        List.of(
                                "mvn",
                                "-f", rootPom.toString(),
                                "-pl", moduleSelector,
                                "-am",
                                "-DskipTests",
                                "-Dcheckstyle.skip=true",
                                "test-compile"));
            } catch (IOException exception) {
                failures.add("Unable to compile test bytecode for Maven module "
                        + module + " without running tests: "
                        + safeMessage(exception));
                return false;
            }
        }

        if (rootIsIncluded) {
            try {
                MavenCommandRunner.run(
                        root,
                        List.of(
                                "mvn",
                                "-DskipTests",
                                "-Dcheckstyle.skip=true",
                                "test-compile"));
            } catch (IOException exception) {
                failures.add("Unable to compile root-project test bytecode "
                        + "without running tests: " + safeMessage(exception));
                return false;
            }
        }

        return true;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static boolean hasJavaTestSources(Path sourceDirectory)
            throws IOException {
        try (var sources = Files.walk(sourceDirectory)) {
            return sources.anyMatch(path ->
                    Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".java"));
        }
    }

    private static boolean hasTestSourcesOrClasses(Path module)
            throws IOException {
        Path sourceDirectory = module.resolve("src/test");
        if (Files.isDirectory(sourceDirectory)
                && hasJavaTestSources(sourceDirectory)) {
            return true;
        }
        return testOutputDirectories(module).stream().anyMatch(Files::isDirectory);
    }

    private static List<Path> testOutputDirectories(Path module)
            throws IOException {
        Path pom = module.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return List.of(module.resolve("target/test-classes"));
        }

        Element project = readXml(pom).getDocumentElement();
        Element build = directChild(project, "build");
        String configuredBuildDirectory = build == null
                ? ""
                : directChildText(build, "directory");
        if (configuredBuildDirectory.isBlank()) {
            configuredBuildDirectory = "target";
        }
        Path buildDirectory = resolveBuildPath(
                configuredBuildDirectory, module, "target");

        LinkedHashSet<Path> outputs = new LinkedHashSet<>();
        String configuredTestOutput = build == null
                ? ""
                : directChildText(build, "testOutputDirectory");
        outputs.add(resolveBuildPath(
                configuredTestOutput.isBlank()
                        ? "${project.build.directory}/test-classes"
                        : configuredTestOutput,
                module,
                buildDirectory.toString()));

        Element plugins = build == null ? null : directChild(build, "plugins");
        if (plugins != null) {
            for (Element plugin : descendants(plugins, "plugin")) {
                if (!"maven-compiler-plugin".equals(
                        directChildText(plugin, "artifactId"))) {
                    continue;
                }
                addTestCompilerOutput(
                        outputs, directChild(plugin, "configuration"),
                        module, buildDirectory.toString(), false);
                Element executions = directChild(plugin, "executions");
                if (executions == null) {
                    continue;
                }
                for (Element execution : descendants(executions, "execution")) {
                    Element goals = directChild(execution, "goals");
                    boolean compilesTests = goals != null
                            && descendants(goals, "goal").stream()
                                    .anyMatch(goal -> "testCompile".equals(
                                            goal.getTextContent().trim()));
                    if (compilesTests) {
                        addTestCompilerOutput(
                                outputs, directChild(execution, "configuration"),
                                module, buildDirectory.toString(), true);
                    }
                }
            }
        }
        return outputs.stream().map(Path::normalize).distinct().toList();
    }

    private static void addTestCompilerOutput(
            Set<Path> outputs,
            Element configuration,
            Path module,
            String buildDirectory,
            boolean testCompileExecution) throws IOException {
        if (configuration == null) {
            return;
        }
        String output = directChildText(configuration, "testOutputDirectory");
        if (output.isBlank() && testCompileExecution) {
            output = directChildText(configuration, "outputDirectory");
        }
        if (!output.isBlank()) {
            outputs.add(resolveBuildPath(output, module, buildDirectory));
        }
    }

    private static Path resolveBuildPath(
            String value,
            Path module,
            String buildDirectory) throws IOException {
        String resolved = value
                .replace("${project.basedir}", module.toAbsolutePath().normalize().toString())
                .replace("${basedir}", module.toAbsolutePath().normalize().toString())
                .replace("${project.build.directory}", buildDirectory);
        if (resolved.contains("${")) {
            throw new IOException("Unresolved Maven test output directory expression in "
                    + module.resolve("pom.xml") + ": " + value);
        }
        Path output = Path.of(resolved);
        return (output.isAbsolute() ? output : module.resolve(output))
                .toAbsolutePath().normalize();
    }

    private static boolean hasSourceNewerThan(
            Path sourceDirectory,
            Path newestClass) throws IOException {

        long classModified = Files.getLastModifiedTime(newestClass).toMillis();
        try (var sources = Files.walk(sourceDirectory)) {
            return sources
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .anyMatch(path -> modifiedAfter(path, classModified));
        }
    }

    private static int compareModifiedTime(Path first, Path second) {
        try {
            return Files.getLastModifiedTime(first)
                    .compareTo(Files.getLastModifiedTime(second));
        } catch (IOException exception) {
            return 0;
        }
    }

    private static boolean modifiedAfter(Path path, long timestamp) {
        try {
            return Files.getLastModifiedTime(path).toMillis() > timestamp;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void mapMavenInvokerFixtures(
            Path root,
            List<Path> modules,
            DependencyGraph graph,
            Set<String> affectedMethods,
            Map<String, Set<String>> testsByAffectedMethod,
            List<String> failures) throws IOException {

        Path invokerTests = root.resolve("src/it");
        if (!Files.isDirectory(invokerTests)
                || !isMavenPluginProject(root.resolve("pom.xml"))) {
            return;
        }

        Path descriptor = root.resolve(
                "target/classes/META-INF/maven/plugin.xml");
        if (!Files.isRegularFile(descriptor)) {
            return;
        }

        Map<String, InvokerGoal> goals = readPluginGoals(descriptor);
        Map<String, String> classHierarchy = applicationClassHierarchy(modules);
        Map<MethodKey, String> methods = applicationMethods(graph);
        Map<String, Set<String>> calls = applicationCalls(graph);

        try (var fixtureDirectories = Files.list(invokerTests)) {
            for (Path fixture : fixtureDirectories
                    .filter(Files::isDirectory).sorted().toList()) {

                Path fixturePom = fixture.resolve("pom.xml");
                if (!Files.isRegularFile(fixturePom)) {
                    continue;
                }

                List<InvokerGoal> fixtureGoals = configuredInvokerGoals(
                        fixturePom, root.resolve("pom.xml"), goals);

                for (InvokerGoal goal : fixtureGoals) {
                    String testCase = "src/it/" + fixture.getFileName()
                            + "#" + goal.name();

                    for (MethodKey entryPoint : goalEntryPoints(
                            goal, classHierarchy, methods)) {

                        Set<String> reached = new LinkedHashSet<>();
                        traceApplicationMethod(
                                methods.get(entryPoint),
                                calls,
                                new LinkedHashSet<>(),
                                reached);

                        for (String affected : affectedMethods) {
                            if (reached.contains(affected)) {
                                testsByAffectedMethod
                                        .computeIfAbsent(
                                                affected,
                                                ignored -> new LinkedHashSet<>())
                                        .add(testCase);
                            }
                        }
                    }
                }
            }
        } catch (IOException exception) {
            failures.add("Unable to inspect Maven Invoker integration-test fixtures: "
                    + exception.getMessage());
        }
    }

    private static boolean isMavenPluginProject(Path pom) throws IOException {
        var document = readXml(pom);
        Element project = document.getDocumentElement();

        if ("maven-plugin".equals(directChildText(project, "packaging"))) {
            return true;
        }

        return descendants(project, "artifactId").stream()
                .anyMatch(element -> "maven-invoker-plugin".equals(
                        element.getTextContent().trim()));
    }

    private static Map<String, InvokerGoal> readPluginGoals(Path descriptor)
            throws IOException {

        var document = readXml(descriptor);
        Map<String, InvokerGoal> goals = new LinkedHashMap<>();

        for (Element mojo : descendants(document.getDocumentElement(), "mojo")) {
            String goal = directChildText(mojo, "goal");
            String implementation = directChildText(mojo, "implementation");
            boolean report = "report".equals(goal)
                    || Boolean.parseBoolean(directChildText(mojo, "requiresReports"));

            if (!goal.isBlank() && !implementation.isBlank()) {
                goals.put(goal, new InvokerGoal(
                        goal, implementation.replace('.', '/'), report));
            }
        }

        return goals;
    }

    private static List<InvokerGoal> configuredInvokerGoals(
            Path fixturePom,
            Path projectPom,
            Map<String, InvokerGoal> descriptorGoals) throws IOException {

        var fixtureDocument = readXml(fixturePom);
        var projectDocument = readXml(projectPom);
        Element projectElement = projectDocument.getDocumentElement();

        String groupId = directChildText(projectElement, "groupId");
        String artifactId = directChildText(projectElement, "artifactId");

        if (groupId.isBlank()) {
            Element parent = directChild(projectElement, "parent");
            groupId = parent == null ? "" : directChildText(parent, "groupId");
        }

        List<InvokerGoal> configuredGoals = new ArrayList<>();

        for (Element plugin : descendants(
                fixtureDocument.getDocumentElement(), "plugin")) {

            String configuredGroup = normalizePluginCoordinate(
                    directChildText(plugin, "groupId"), groupId, artifactId);
            String configuredArtifact = normalizePluginCoordinate(
                    directChildText(plugin, "artifactId"), groupId, artifactId);

            if (!groupId.equals(configuredGroup)
                    || !artifactId.equals(configuredArtifact)) {
                continue;
            }

            if (hasAncestor(plugin, "reporting")) {
                List<String> reports = descendantsText(plugin, "report");

                if (reports.isEmpty()) {
                    descriptorGoals.values().stream()
                            .filter(InvokerGoal::report)
                            .filter(goal -> !"aggregate".equals(goal.name()))
                            .forEach(configuredGoals::add);
                } else {
                    for (String report : reports) {
                        InvokerGoal goal = descriptorGoals.get(report);
                        if (goal != null) {
                            configuredGoals.add(goal);
                        }
                    }
                }
            } else if (hasAncestor(plugin, "build")) {
                Element executions = directChild(plugin, "executions");
                if (executions != null) {
                    for (Element executionGoal : descendants(executions, "goal")) {
                        InvokerGoal goal = descriptorGoals.get(
                                executionGoal.getTextContent().trim());
                        if (goal != null) {
                            configuredGoals.add(goal);
                        }
                    }
                }
            }
        }

        return configuredGoals.stream().distinct().toList();
    }

    private static String normalizePluginCoordinate(
            String coordinate, String groupId, String artifactId) {

        if ("@project.groupId@".equals(coordinate)
                || "${project.groupId}".equals(coordinate)) {
            return groupId;
        }

        if ("@project.artifactId@".equals(coordinate)
                || "${project.artifactId}".equals(coordinate)) {
            return artifactId;
        }

        return coordinate;
    }

    private static List<MethodKey> goalEntryPoints(
            InvokerGoal goal,
            Map<String, String> classHierarchy,
            Map<MethodKey, String> applicationMethods) {

        Set<String> owners = new LinkedHashSet<>();
        String owner = goal.implementation();

        while (owner != null && !owner.isBlank() && owners.add(owner)) {
            owner = classHierarchy.get(owner);
        }

        String expectedMethod = goal.report() ? "executeReport" : "execute";

        return applicationMethods.keySet().stream()
                .filter(method -> owners.contains(method.owner()))
                .filter(method -> expectedMethod.equals(method.name()))
                .toList();
    }

    private static Map<String, String> applicationClassHierarchy(
            List<Path> modules) throws IOException {

        Map<String, String> hierarchy = new LinkedHashMap<>();

        for (Path module : modules) {
            Path classes = module.resolve("target/classes");
            if (!Files.isDirectory(classes)) {
                continue;
            }

            try (var files = Files.walk(classes)) {
                for (Path classFile : files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".class"))
                        .toList()) {

                    try (InputStream input = Files.newInputStream(classFile)) {
                        new ClassReader(input).accept(
                                new ClassVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(
                                            int version,
                                            int access,
                                            String name,
                                            String signature,
                                            String superName,
                                            String[] interfaces) {
                                        hierarchy.put(name, superName);
                                    }
                                },
                                ClassReader.SKIP_CODE
                                        | ClassReader.SKIP_DEBUG
                                        | ClassReader.SKIP_FRAMES);
                    }
                }
            }
        }

        return hierarchy;
    }

    private static org.w3c.dom.Document readXml(Path file) throws IOException {
        try {
            return secureDocumentBuilderFactory()
                    .newDocumentBuilder()
                    .parse(file.toFile());
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Unable to read XML file " + file + ": "
                    + exception.getMessage(), exception);
        }
    }

    private static Element directChild(Element parent, String name) {
        for (Node child = parent.getFirstChild();
             child != null;
             child = child.getNextSibling()) {

            if (child instanceof Element element
                    && localName(element).equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static String directChildText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static String localName(Element element) {
        return element.getLocalName() == null
                ? element.getTagName()
                : element.getLocalName();
    }

    private static boolean hasAncestor(Element element, String name) {
        for (Node parent = element.getParentNode();
             parent instanceof Element;
             parent = parent.getParentNode()) {

            if (localName((Element) parent).equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> descendantsText(Element element, String name) {
        List<String> texts = new ArrayList<>();
        for (Element descendant : descendants(element, name)) {
            String text = descendant.getTextContent().trim();
            if (!text.isEmpty()) {
                texts.add(text);
            }
        }
        return texts;
    }

    private static List<Element> descendants(Element parent, String name) {
        List<Element> matches = new ArrayList<>();

        for (Node child = parent.getFirstChild();
             child != null;
             child = child.getNextSibling()) {

            if (child instanceof Element element) {
                if (localName(element).equals(name)) {
                    matches.add(element);
                }
                matches.addAll(descendants(element, name));
            }
        }

        return matches;
    }

    private record InvokerGoal(String name, String implementation, boolean report) {
    }

    private static CoverageResult legacyCoverageStatus(
            List<Path> modules,
            List<String> affectedMethods,
            List<String> directlyImpactedMethods,
            List<String> indirectlyAffectedMethods) throws IOException {

        List<String> failures = new ArrayList<>();
        Map<String, DependencyGraphResult.TestCoverageStatus> testCases =
                new LinkedHashMap<>();

        boolean foundCoverageReport = false;
        boolean foundTestReport = false;

        for (Path module : modules) {
            Path targetDirectory = module.resolve("target");
            if (!Files.isDirectory(targetDirectory)) {
                continue;
            }

            foundCoverageReport |= !findCoverageReports(targetDirectory).isEmpty();

            for (Path report : findTestReports(targetDirectory)) {
                foundTestReport = true;
                for (DependencyGraphResult.TestCoverageStatus test : readTestCases(report)) {
                    testCases.putIfAbsent(
                            test.testClass() + "#" + test.testMethod(), test);
                }
            }
        }

        if (!foundCoverageReport) {
            failures.add("No JaCoCo XML coverage report was found in the Maven modules.");
        }

        if (!foundTestReport) {
            failures.add("No Surefire or Failsafe XML test report was found in the Maven modules.");
        }

        List<DependencyGraphResult.AffectedMethodTestGroup> groups = new ArrayList<>();
        for (String method : new LinkedHashSet<>(affectedMethods)) {
            groups.add(new DependencyGraphResult.AffectedMethodTestGroup(
                    method,
                    impactTypeFor(
                            method, directlyImpactedMethods, indirectlyAffectedMethods),
                    List.of(),
                    "N/A",
                    TEST_SELECTION_UNAVAILABLE));
        }

        return new CoverageResult(
                List.of(),
                List.of(),
                groups,
                failures,
                new ArrayList<>(testCases.values()),
                TEST_SELECTION_UNAVAILABLE);
    }

    private static void readTestBytecode(
            List<Path> testClassesDirectories,
            Path moduleRoot,
            Map<MethodKey, Set<MethodKey>> calls,
            Set<TestEntryPoint> testEntryPoints) throws IOException {
        Map<String, TestClassInfo> testClassesByName = new LinkedHashMap<>();

        for (Path testClasses : testClassesDirectories) {
            try (var files = Files.walk(testClasses)) {
                for (Path classFile : files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".class"))
                        .toList()) {

                    try (InputStream input = Files.newInputStream(classFile)) {
                        new ClassReader(input).accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                private String className;
                                private String superName;
                                private int classAccess;
                                private final Map<MethodKey, TestMethodInfo> methods =
                                        new LinkedHashMap<>();

                                @Override
                                public void visit(
                                        int version,
                                        int access,
                                        String name,
                                        String signature,
                                        String superName,
                                        String[] interfaces) {
                                    className = name;
                                    this.superName = superName;
                                    classAccess = access;
                                }

                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {

                                    MethodKey method =
                                            new MethodKey(className, name, descriptor);
                                    Set<MethodKey> invokedMethods = calls.computeIfAbsent(
                                            method, ignored -> new LinkedHashSet<>());
                                    TestMethodInfo methodInfo =
                                            new TestMethodInfo(method, access);

                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                                String annotationDescriptor,
                                                boolean visible) {
                                            if (isTestAnnotation(annotationDescriptor)) {
                                                methodInfo.annotated = true;
                                            }
                                            return null;
                                        }

                                        @Override
                                        public void visitMethodInsn(
                                                int opcode,
                                                String owner,
                                                String methodName,
                                                String methodDescriptor,
                                                boolean isInterface) {
                                            invokedMethods.add(new MethodKey(
                                                    owner, methodName, methodDescriptor));
                                        }

                                        @Override
                                        public void visitEnd() {
                                            methods.put(method, methodInfo);
                                            if (methodInfo.annotated) {
                                                testEntryPoints.add(new TestEntryPoint(
                                                        method, className, moduleRoot));
                                            }
                                        }

                                        @Override
                                        public void visitInvokeDynamicInsn(
                                                String dynamicName,
                                                String dynamicDescriptor,
                                                Handle bootstrapMethodHandle,
                                                Object... bootstrapMethodArguments) {
                                            for (Object argument : bootstrapMethodArguments) {
                                                collectMethodHandles(argument, invokedMethods);
                                            }
                                        }
                                    };
                                }

                                @Override
                                public void visitEnd() {
                                    testClassesByName.put(
                                            className,
                                            new TestClassInfo(
                                                    className,
                                                    superName,
                                                    classAccess,
                                                    methods));
                                }
                            },
                                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    }
                }
            }
        }

        for (TestClassInfo testClass : testClassesByName.values()) {
            if ((testClass.access() & Opcodes.ACC_ABSTRACT) != 0
                    || !extendsJUnit3TestCase(testClass, testClassesByName)) {
                continue;
            }

            Map<String, TestMethodInfo> inheritedTests = new LinkedHashMap<>();
            String currentClass = testClass.internalName();
            Set<String> visited = new HashSet<>();
            while (currentClass != null && visited.add(currentClass)) {
                TestClassInfo current = testClassesByName.get(currentClass);
                if (current == null) {
                    break;
                }
                for (TestMethodInfo method : current.methods().values()) {
                    if (isJUnit3TestMethod(method)) {
                        inheritedTests.putIfAbsent(
                                method.method().name() + method.method().descriptor(),
                                method);
                    }
                }
                currentClass = current.superName();
            }

            for (TestMethodInfo test : inheritedTests.values()) {
                testEntryPoints.add(new TestEntryPoint(
                        test.method(), testClass.internalName(), moduleRoot));
            }
        }
    }

    private static boolean extendsJUnit3TestCase(
            TestClassInfo testClass,
            Map<String, TestClassInfo> testClassesByName) {
        String current = testClass.superName();
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            if ("junit/framework/TestCase".equals(current)) {
                return true;
            }
            TestClassInfo parent = testClassesByName.get(current);
            if (parent == null) {
                return false;
            }
            current = parent.superName();
        }
        return false;
    }

    private static boolean isJUnit3TestMethod(TestMethodInfo method) {
        return method.method().name().startsWith("test")
                && "()V".equals(method.method().descriptor())
                && (method.access() & Opcodes.ACC_PUBLIC) != 0
                && (method.access() & Opcodes.ACC_STATIC) == 0;
    }

    private static void collectMethodHandles(
            Object value,
            Set<MethodKey> invokedMethods) {

        if (value instanceof Handle handle) {
            int tag = handle.getTag();

            if (tag >= Opcodes.H_INVOKEVIRTUAL
                    && tag <= Opcodes.H_INVOKEINTERFACE) {
                invokedMethods.add(new MethodKey(
                        handle.getOwner(),
                        handle.getName(),
                        handle.getDesc()));
            }
        } else if (value instanceof ConstantDynamic constant) {
            collectMethodHandles(constant.getBootstrapMethod(), invokedMethods);

            for (int i = 0; i < constant.getBootstrapMethodArgumentCount(); i++) {
                collectMethodHandles(
                        constant.getBootstrapMethodArgument(i), invokedMethods);
            }
        }
    }

    private static boolean isTestAnnotation(String descriptor) {
        return switch (descriptor) {
            case "Lorg/junit/Test;",
                    "Lorg/junit/jupiter/api/Test;",
                    "Lorg/junit/jupiter/params/ParameterizedTest;",
                    "Lorg/junit/jupiter/api/RepeatedTest;",
                    "Lorg/junit/jupiter/api/TestFactory;",
                    "Lorg/junit/jupiter/api/TestTemplate;",
                    "Lorg/testng/annotations/Test;" -> true;
            default -> false;
        };
    }

    private static Map<MethodKey, String> applicationMethods(DependencyGraph graph) {
        Map<MethodKey, String> methods = new LinkedHashMap<>();
        Set<MethodKey> ambiguousMethods = new HashSet<>();
        if (graph == null) {
            return methods;
        }

        for (GraphNode node : graph.getNodes()) {
            if (node.type() != GraphNode.Type.METHOD) {
                continue;
            }

            Object className = node.metadata().get("className");
            Object methodName = node.metadata().get("methodName");
            Object descriptor = node.metadata().get("descriptor");

            if (className instanceof String owner
                    && methodName instanceof String name
                    && descriptor instanceof String methodDescriptor) {
                MethodKey key = new MethodKey(
                        owner.replace('.', '/'), name, methodDescriptor);
                if (methods.containsKey(key)) {
                    methods.remove(key);
                    ambiguousMethods.add(key);
                } else if (!ambiguousMethods.contains(key)) {
                    methods.put(key, node.id());
                }
            }
        }

        return methods;
    }

    private static Map<String, Set<String>> applicationCalls(DependencyGraph graph) {
        Map<String, Set<String>> calls = new LinkedHashMap<>();
        if (graph == null) {
            return calls;
        }

        for (GraphEdge edge : graph.getEdges()) {
            if ("CALLS".equals(edge.type())) {
                calls.computeIfAbsent(
                        edge.source(), ignored -> new LinkedHashSet<>())
                        .add(edge.target());
            }
        }

        return calls;
    }

    private static void traceTestMethod(
            MethodKey method,
            Map<MethodKey, Set<MethodKey>> testCalls,
            Map<MethodKey, String> applicationMethods,
            Map<String, Set<String>> applicationCalls,
            Set<MethodKey> visitedTestMethods,
            Set<String> reachedApplicationMethods) {

        if (!visitedTestMethods.add(method)) {
            return;
        }

        String applicationMethod = applicationMethods.get(method);
        if (applicationMethod != null) {
            traceApplicationMethod(
                    applicationMethod,
                    applicationCalls,
                    new LinkedHashSet<>(),
                    reachedApplicationMethods);
            return;
        }

        for (MethodKey invokedMethod : testCalls.getOrDefault(method, Set.of())) {
            traceTestMethod(
                    invokedMethod,
                    testCalls,
                    applicationMethods,
                    applicationCalls,
                    visitedTestMethods,
                    reachedApplicationMethods);
        }
    }

    private static void traceApplicationMethod(
            String method,
            Map<String, Set<String>> applicationCalls,
            Set<String> visited,
            Set<String> reachedApplicationMethods) {

        if (!visited.add(method)) {
            return;
        }

        reachedApplicationMethods.add(method);
        for (String callee : applicationCalls.getOrDefault(method, Set.of())) {
            traceApplicationMethod(
                    callee, applicationCalls, visited, reachedApplicationMethods);
        }
    }

    private record MethodKey(String owner, String name, String descriptor) {
    }

    private record TestEntryPoint(
            MethodKey method, String testClass, Path moduleRoot) {
    }

    private record TestClassInfo(
            String internalName,
            String superName,
            int access,
            Map<MethodKey, TestMethodInfo> methods) {
    }

    private static final class TestMethodInfo {
        private final MethodKey method;
        private final int access;
        private boolean annotated;

        private TestMethodInfo(MethodKey method, int access) {
            this.method = method;
            this.access = access;
        }

        private MethodKey method() {
            return method;
        }

        private int access() {
            return access;
        }
    }

    private static List<Path> discoverModules(Path root) throws IOException {
        Path rootPom = root.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            throw new IOException("No Maven pom.xml file was found at project root: " + root);
        }

        Set<Path> modules = new LinkedHashSet<>();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        pending.add(root);

        while (!pending.isEmpty()) {
            Path moduleRoot = pending.removeFirst();
            if (!modules.add(moduleRoot)) {
                continue;
            }

            for (String modulePath : readModulePaths(moduleRoot.resolve("pom.xml"))) {
                Path child = moduleRoot.resolve(modulePath).normalize();
                if (child.startsWith(root)
                        && Files.isRegularFile(child.resolve("pom.xml"))) {
                    pending.addLast(child);
                }
            }
        }

        return modules.stream().sorted().toList();
    }

    private static List<String> readModulePaths(Path pom) throws IOException {
        try {
            var document = secureDocumentBuilderFactory()
                    .newDocumentBuilder().parse(pom.toFile());
            List<String> modulePaths = new ArrayList<>();

            for (Element element : descendants(document.getDocumentElement(), "module")) {
                String path = element.getTextContent().trim();
                if (!path.isEmpty()) {
                    modulePaths.add(path);
                }
            }

            return modulePaths;
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Unable to read Maven module declarations from "
                    + pom + ": " + exception.getMessage(), exception);
        }
    }

    private static List<DependencyGraphResult.TestCoverageStatus> readTestCases(
            Path report) throws IOException {

        try {
            var document = secureDocumentBuilderFactory()
                    .newDocumentBuilder().parse(report.toFile());
            List<DependencyGraphResult.TestCoverageStatus> tests = new ArrayList<>();

            for (Element testCase : descendants(
                    document.getDocumentElement(), "testcase")) {
                String testClass = testCase.getAttribute("classname").trim();
                String testMethod = testCase.getAttribute("name").trim();

                if (!testClass.isEmpty() && !testMethod.isEmpty()) {
                    tests.add(new DependencyGraphResult.TestCoverageStatus(
                            testClass,
                            testMethod,
                            "N/A",
                            TEST_SELECTION_UNAVAILABLE));
                }
            }

            return tests;
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Unable to read test report " + report
                    + ": " + exception.getMessage(), exception);
        }
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory()
            throws ParserConfigurationException {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static List<Path> findCoverageReports(Path targetDirectory)
            throws IOException {
        try (var paths = Files.walk(targetDirectory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("jacoco") && name.endsWith(".xml");
                    })
                    .toList();
        }
    }

    private static String describeJaCoCoArtifacts(List<Path> modules)
            throws IOException {
        List<Path> executionData = new ArrayList<>();
        List<Path> reports = new ArrayList<>();
        long newestClassTime = 0;

        for (Path module : modules) {
            Path targetDirectory = module.resolve("target");
            if (!Files.isDirectory(targetDirectory)) {
                continue;
            }
            try (var paths = Files.walk(targetDirectory)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String name = path.getFileName().toString();
                    if (name.endsWith(".exec")) {
                        executionData.add(path);
                    } else if (name.startsWith("jacoco") && name.endsWith(".xml")) {
                        reports.add(path);
                    }
                    if (name.endsWith(".class")) {
                        newestClassTime = Math.max(
                                newestClassTime,
                                Files.getLastModifiedTime(path).toMillis());
                    }
                }
            }
        }

        if (executionData.isEmpty() && reports.isEmpty()) {
            return "JaCoCo data is missing: no .exec execution data or JaCoCo XML report "
                    + "was found in Maven module target directories.";
        }

        List<String> states = new ArrayList<>();
        if (!executionData.isEmpty()) {
            boolean allEmpty = true;
            long newestExecTime = 0;
            for (Path data : executionData) {
                allEmpty &= Files.size(data) == 0;
                newestExecTime = Math.max(
                        newestExecTime,
                        Files.getLastModifiedTime(data).toMillis());
            }
            if (allEmpty) {
                states.add("JaCoCo .exec data is empty.");
            } else if (newestExecTime < newestClassTime) {
                states.add("JaCoCo .exec data is stale relative to compiled classes.");
            } else {
                states.add("JaCoCo .exec data is present but is not consumed for test attribution.");
            }
        } else {
            states.add("JaCoCo .exec execution data is missing.");
        }

        if (!reports.isEmpty()) {
            boolean validXml = true;
            Path malformed = null;
            long newestReportTime = 0;
            for (Path report : reports) {
                try {
                    readXml(report);
                } catch (IOException exception) {
                    validXml = false;
                    malformed = report;
                    break;
                }
                newestReportTime = Math.max(
                        newestReportTime,
                        Files.getLastModifiedTime(report).toMillis());
            }
            if (!validXml) {
                states.add("JaCoCo XML parsing failed for " + malformed + ".");
            } else if (newestReportTime < newestClassTime) {
                states.add("JaCoCo XML report is stale relative to compiled classes.");
            } else {
                states.add("JaCoCo XML is aggregate method coverage and has no per-test attribution.");
            }
        } else {
            states.add("JaCoCo XML report is missing.");
        }
        return String.join(" ", states);
    }

    private static List<Path> findTestReports(Path targetDirectory)
            throws IOException {
        try (var paths = Files.walk(targetDirectory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        Path parent = path.getParent();
                        if (parent == null) {
                            return false;
                        }

                        String directory = parent.getFileName().toString();
                        String name = path.getFileName().toString();

                        return (directory.equals("surefire-reports")
                                || directory.equals("failsafe-reports"))
                                && name.startsWith("TEST-")
                                && name.endsWith(".xml");
                    })
                    .toList();
        }
    }

    private static String impactTypeFor(
            String method,
            List<String> directlyImpactedMethods,
            List<String> indirectlyAffectedMethods) {

        if (directlyImpactedMethods != null
                && directlyImpactedMethods.contains(method)) {
            return "DIRECT";
        }

        if (indirectlyAffectedMethods != null
                && indirectlyAffectedMethods.contains(method)) {
            return "INDIRECT";
        }

        return "DIRECT";
    }

    private static List<DependencyGraphResult.AffectedMethodTestGroup>
    buildUnavailableGroups(
            List<String> affectedMethods,
            List<String> directlyImpactedMethods,
            List<String> indirectlyAffectedMethods,
            String reason) {

        List<DependencyGraphResult.AffectedMethodTestGroup> groups = new ArrayList<>();

        for (String method : new LinkedHashSet<>(affectedMethods)) {
            groups.add(new DependencyGraphResult.AffectedMethodTestGroup(
                    method,
                    impactTypeFor(
                            method, directlyImpactedMethods, indirectlyAffectedMethods),
                    List.of(),
                    "NOT ANALYZED",
                    reason));
        }

        return groups;
    }

    public record CoverageResult(
            List<DependencyGraphResult.MethodTestCoverage> methodTestCoverage,
            List<String> selectedTests,
            List<DependencyGraphResult.AffectedMethodTestGroup> groupedSelectedTests,
            List<String> failures,
            List<DependencyGraphResult.TestCoverageStatus> testCoverageStatuses,
            String selectionNote) {

        public CoverageResult(
                List<DependencyGraphResult.MethodTestCoverage> methodTestCoverage,
                List<String> selectedTests,
                List<DependencyGraphResult.AffectedMethodTestGroup> groupedSelectedTests,
                List<String> failures,
                String selectionNote) {
            this(methodTestCoverage, selectedTests, groupedSelectedTests,
                    failures, List.of(), selectionNote);
        }

        public CoverageResult {
            methodTestCoverage = methodTestCoverage == null
                    ? List.of() : List.copyOf(methodTestCoverage);
            selectedTests = selectedTests == null
                    ? List.of() : List.copyOf(selectedTests);
            groupedSelectedTests = groupedSelectedTests == null
                    ? List.of() : List.copyOf(groupedSelectedTests);
            failures = failures == null ? List.of() : List.copyOf(failures);
            testCoverageStatuses = testCoverageStatuses == null
                    ? List.of() : List.copyOf(testCoverageStatuses);
            selectionNote = selectionNote == null ? "" : selectionNote;
        }
    }
}