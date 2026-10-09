package smartdeptest.analysis;

import smartdeptest.graph.DependencyGraph;
import smartdeptest.graph.DependencyGraphResult;
import smartdeptest.graph.GraphEdge;
import smartdeptest.graph.GraphNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

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
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

public final class JacocoMethodTestMapper {

    private static final String TEST_SELECTION_UNAVAILABLE =
            "JaCoCo XML reports contain aggregate method coverage, not test-method attribution. "
                    + "No regression test is selected without per-test coverage evidence.";

    public CoverageResult map(
            Path projectDirectory,
            List<String> affectedMethods) throws IOException {

        return map(
                projectDirectory,
                affectedMethods,
                List.of(),
                List.of());
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
            List<String> indirectlyAffectedMethods)
            throws IOException {

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
            DependencyGraph applicationGraph)
            throws IOException {

        if (projectDirectory == null
                || !Files.isDirectory(projectDirectory)) {

            throw new IOException(
                    "Project directory does not exist or is not a directory: "
                            + projectDirectory);
        }

        if (affectedMethods == null
                || affectedMethods.isEmpty()) {

            return new CoverageResult(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "");
        }

        Path root =
                projectDirectory
                        .toAbsolutePath()
                        .normalize();

        List<Path> modules =
                discoverModules(root);

        if (applicationGraph == null) {

            return legacyCoverageStatus(
                    modules,
                    affectedMethods,
                    directlyImpactedMethods,
                    indirectlyAffectedMethods);
        }

        List<String> failures =
                new ArrayList<>();

        /*
         * First identify the Maven module(s) owning the affected
         * application methods.
         *
         * The graph now carries modulePath metadata, so that is the
         * first source of truth.
         *
         * Existing bytecode/source fallbacks are still preserved.
         */
        List<Path> affectedModules =
                identifyAffectedApplicationModules(
                        root,
                        modules,
                        affectedMethods,
                        applicationGraph);

        /*
         * Maven Invoker integration-test fixtures are special.
         *
         * They can exercise application/plugin methods even when the
         * affected application module cannot be identified through
         * normal test-source/module discovery.
         *
         * Therefore do NOT return immediately when affectedModules
         * is empty. The existing Invoker mapping must get a chance
         * to run.
         */
        Map<String, Set<String>> testsByAffectedMethod =
                new LinkedHashMap<>();

        for (String method :
                new LinkedHashSet<>(affectedMethods)) {

            testsByAffectedMethod.put(
                    method,
                    new LinkedHashSet<>());
        }

        /*
         * Maven Invoker projects are handled separately.
         *
         * Keep this generic and preserve the existing behavior.
         */
        mapMavenInvokerFixtures(
                root,
                modules,
                applicationGraph,
                new LinkedHashSet<>(affectedMethods),
                testsByAffectedMethod,
                failures);

        /*
         * If no normal Maven module was identified, but Invoker
         * fixture mapping successfully found tests, return those
         * results instead of incorrectly reporting the entire
         * analysis as unavailable.
         */
        if (affectedModules.isEmpty()) {

            boolean fixtureTestsFound =
                    testsByAffectedMethod
                            .values()
                            .stream()
                            .anyMatch(
                                    tests -> !tests.isEmpty());

            if (fixtureTestsFound) {

                return buildCoverageResult(
                        affectedMethods,
                        directlyImpactedMethods,
                        indirectlyAffectedMethods,
                        testsByAffectedMethod,
                        true,
                        failures);
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

        /*
         * Only affected application modules are candidates for test
         * compilation.
         *
         * Maven -am automatically includes required upstream reactor
         * modules.
         */
        boolean testBytecodeReady =
                ensureTestBytecodeCurrent(
                        root,
                        affectedModules,
                        failures);

        Map<MethodKey, Set<MethodKey>> testCalls =
                new LinkedHashMap<>();

        Set<MethodKey> testEntryPoints =
                new LinkedHashSet<>();

        if (testBytecodeReady) {

            /*
             * Read test bytecode only from affected application modules.
             */
            for (Path moduleRoot :
                    affectedModules) {

                Path targetDirectory =
                        moduleRoot.resolve("target");

                if (!Files.isDirectory(targetDirectory)) {
                    continue;
                }

                Path testClasses =
                        targetDirectory.resolve("test-classes");

                if (Files.isDirectory(testClasses)) {

                    readTestBytecode(
                            testClasses,
                            testCalls,
                            testEntryPoints);
                }
            }
        }

        Map<MethodKey, String> applicationMethods =
                applicationMethods(applicationGraph);

        Map<String, Set<String>> callsByApplicationMethod =
                applicationCalls(applicationGraph);

        Set<String> affectedSet =
                new LinkedHashSet<>(affectedMethods);

        /*
         * Map normal JUnit/TestNG tests through the application
         * call graph.
         */
        for (MethodKey entryPoint :
                testEntryPoints) {

            String testId =
                    entryPoint.owner().replace('/', '.')
                            + "#"
                            + entryPoint.name();

            Set<String> reachedApplicationMethods =
                    new LinkedHashSet<>();

            traceTestMethod(
                    entryPoint,
                    testCalls,
                    applicationMethods,
                    callsByApplicationMethod,
                    new HashSet<>(),
                    reachedApplicationMethods);

            for (String affectedMethod :
                    affectedSet) {

                if (reachedApplicationMethods.contains(
                        affectedMethod)) {

                    testsByAffectedMethod
                            .computeIfAbsent(
                                    affectedMethod,
                                    ignored ->
                                            new LinkedHashSet<>())
                            .add(testId);
                }
            }
        }

        /*
         * Invoker fixtures were already mapped above.
         */

        return buildCoverageResult(
                affectedMethods,
                directlyImpactedMethods,
                indirectlyAffectedMethods,
                testsByAffectedMethod,
                testBytecodeReady,
                failures);
    }

    /*
     * ============================================================
     * RESULT BUILDING
     * ============================================================
     */

    private static CoverageResult buildCoverageResult(
            List<String> affectedMethods,
            List<String> directlyImpactedMethods,
            List<String> indirectlyAffectedMethods,
            Map<String, Set<String>> testsByAffectedMethod,
            boolean testBytecodeReady,
            List<String> failures) {

        List<DependencyGraphResult.MethodTestCoverage> mappings =
                new ArrayList<>();

        LinkedHashSet<String> selectedTests =
                new LinkedHashSet<>();

        List<DependencyGraphResult.AffectedMethodTestGroup> groups =
                new ArrayList<>();

        for (String method :
                new LinkedHashSet<>(affectedMethods)) {

            List<String> tests =
                    testsByAffectedMethod
                            .getOrDefault(
                                    method,
                                    Set.of())
                            .stream()
                            .sorted()
                            .toList();

            for (String testId :
                    tests) {

                int separator =
                        testId.lastIndexOf('#');

                if (separator <= 0
                        || separator >= testId.length() - 1) {

                    continue;
                }

                String testClass =
                        testId.substring(
                                0,
                                separator);

                String testMethod =
                        testId.substring(
                                separator + 1);

                mappings.add(
                        new DependencyGraphResult.MethodTestCoverage(
                                method,
                                testClass,
                                testMethod));

                selectedTests.add(testId);
            }

            String status;

            if (!testBytecodeReady) {
                status = "NOT ANALYZED";
            } else if (tests.isEmpty()) {
                status = "NONE FOUND";
            } else {
                status = "SELECTED";
            }

            String note =
                    testBytecodeReady
                            ? ""
                            : "Test bytecode compilation failed; "
                            + "coverage attribution was not inferred.";

            groups.add(
                    new DependencyGraphResult.AffectedMethodTestGroup(
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
                "");
    }

    /*
     * ============================================================
     * AFFECTED MODULE IDENTIFICATION
     * ============================================================
     */

    private static List<Path> identifyAffectedApplicationModules(
            Path root,
            List<Path> modules,
            List<String> affectedMethods,
            DependencyGraph applicationGraph)
            throws IOException {

        Set<Path> matchedModules =
                new LinkedHashSet<>();

        /*
         * ========================================================
         * FIRST PREFERENCE: MODULE PATH FROM GRAPH
         * ========================================================
         *
         * The application bytecode scanner already knows the Maven
         * module from which each application class was scanned.
         */
        if (applicationGraph != null) {

            Set<String> affectedMethodIds =
                    new LinkedHashSet<>(affectedMethods);

            for (GraphNode node :
                    applicationGraph.getNodes()) {

                if (node.type()
                        != GraphNode.Type.METHOD) {

                    continue;
                }

                if (!affectedMethodIds.contains(
                        node.id())) {

                    continue;
                }

                Object modulePathValue =
                        node.metadata()
                                .get("modulePath");

                if (!(modulePathValue
                        instanceof String modulePath)
                        || modulePath.isBlank()) {

                    continue;
                }

                try {

                    Path module =
                            Path.of(modulePath)
                                    .toAbsolutePath()
                                    .normalize();

                    if (module.startsWith(
                                root.toAbsolutePath()
                                        .normalize())
                            && Files.isDirectory(module)
                            && Files.isRegularFile(
                                    module.resolve("pom.xml"))) {

                        matchedModules.add(module);
                    }

                } catch (RuntimeException ignored) {
                    /*
                     * Invalid graph metadata must not prevent the
                     * existing fallbacks from running.
                     */
                }
            }
        }

        /*
         * ========================================================
         * EXISTING FALLBACKS
         * ========================================================
         */

        Set<String> affectedOwners =
                new LinkedHashSet<>();

        for (String affectedMethod :
                affectedMethods) {

            String owner =
                    extractOwnerClassName(
                            affectedMethod);

            if (owner != null
                    && !owner.isBlank()) {

                affectedOwners.add(
                        owner.replace('.', '/'));
            }
        }

        if (affectedOwners.isEmpty()) {
            return matchedModules.stream()
                    .sorted()
                    .toList();
        }

        /*
         * --------------------------------------------------------
         * FALLBACK 1: target/classes
         * --------------------------------------------------------
         */
        for (Path module :
                modules) {

            Path classes =
                    module.resolve(
                            "target/classes");

            if (!Files.isDirectory(classes)) {
                continue;
            }

            for (String owner :
                    affectedOwners) {

                Path classFile =
                        classes.resolve(
                                owner + ".class");

                if (Files.isRegularFile(
                        classFile)) {

                    matchedModules.add(module);
                    break;
                }
            }
        }

        /*
         * --------------------------------------------------------
         * FALLBACK 2: src/main/java
         * --------------------------------------------------------
         */
        if (matchedModules.isEmpty()) {

            for (Path module :
                    modules) {

                Path mainJava =
                        module.resolve(
                                "src/main/java");

                if (!Files.isDirectory(
                        mainJava)) {

                    continue;
                }

                for (String owner :
                        affectedOwners) {

                    Path sourceFile =
                            mainJava.resolve(
                                    owner + ".java");

                    if (Files.isRegularFile(
                            sourceFile)) {

                        matchedModules.add(module);
                        break;
                    }
                }
            }
        }

        /*
         * --------------------------------------------------------
         * FALLBACK 3: non-standard source layout
         * --------------------------------------------------------
         */
        if (matchedModules.isEmpty()) {

            for (Path module :
                    modules) {

                for (String owner :
                        affectedOwners) {

                    String relativeSourcePath =
                            owner + ".java";

                    try (var paths =
                                 Files.walk(module)) {

                        boolean found =
                                paths
                                        .filter(Files::isRegularFile)
                                        .anyMatch(
                                                path ->
                                                        path.toString()
                                                                .replace(
                                                                        '\\',
                                                                        '/')
                                                                .endsWith(
                                                                        relativeSourcePath));

                        if (found) {

                            matchedModules.add(module);
                            break;
                        }

                    } catch (IOException ignored) {
                        /*
                         * One unreadable module must not prevent
                         * inspection of the remaining modules.
                         */
                    }
                }
            }
        }

        return matchedModules.stream()
                .sorted()
                .toList();
    }

    private static String extractOwnerClassName(
            String affectedMethod) {

        if (affectedMethod == null
                || affectedMethod.isBlank()) {

            return null;
        }

        int descriptorStart =
                affectedMethod.indexOf('(');

        if (descriptorStart <= 0) {
            return null;
        }

        String methodPart =
                affectedMethod.substring(
                        0,
                        descriptorStart);

        int separator =
                methodPart.lastIndexOf('.');

        if (separator <= 0) {
            return null;
        }

        return methodPart.substring(
                0,
                separator);
    }

    /*
     * ============================================================
     * TEST BYTECODE COMPILATION
     * ============================================================
     */

    private static boolean ensureTestBytecodeCurrent(
            Path root,
            List<Path> affectedModules,
            List<String> failures)
            throws IOException {

        boolean needsCompilation = false;

        List<Path> modulesNeedingCompilation =
                new ArrayList<>();

        for (Path module :
                affectedModules) {

            Path sourceDirectory =
                    module.resolve("src/test");

            if (!Files.isDirectory(
                    sourceDirectory)) {

                continue;
            }

            if (!hasJavaTestSources(
                    sourceDirectory)) {

                continue;
            }

            Path testClasses =
                    module.resolve(
                            "target/test-classes");

            boolean moduleNeedsCompilation =
                    false;

            if (!Files.isDirectory(
                    testClasses)) {

                moduleNeedsCompilation = true;

            } else {

                try (var classes =
                             Files.walk(
                                     testClasses)) {

                    Path newestClass =
                            classes
                                    .filter(Files::isRegularFile)
                                    .filter(
                                            path ->
                                                    path.getFileName()
                                                            .toString()
                                                            .endsWith(".class"))
                                    .max(
                                            JacocoMethodTestMapper
                                                    ::compareModifiedTime)
                                    .orElse(null);

                    if (newestClass == null
                            || hasSourceNewerThan(
                                    sourceDirectory,
                                    newestClass)) {

                        moduleNeedsCompilation = true;
                    }

                } catch (IOException exception) {

                    failures.add(
                            "Unable to inspect test compilation freshness in "
                                    + module
                                    + ": "
                                    + exception.getMessage());

                    return false;
                }
            }

            if (moduleNeedsCompilation) {

                needsCompilation = true;

                modulesNeedingCompilation.add(
                        module);
            }
        }

        if (!needsCompilation) {
            return true;
        }

        Path rootPom =
                root.resolve("pom.xml")
                        .toAbsolutePath()
                        .normalize();

        boolean rootIsIncluded = false;

        for (Path module :
                modulesNeedingCompilation) {

            Path modulePath =
                    module.toAbsolutePath()
                            .normalize();

            if (modulePath.equals(
                    root.toAbsolutePath()
                            .normalize())) {

                rootIsIncluded = true;
                continue;
            }

            Path relativeModule;

            try {

                relativeModule =
                        root.toAbsolutePath()
                                .normalize()
                                .relativize(
                                        modulePath);

            } catch (IllegalArgumentException exception) {

                failures.add(
                        "Unable to determine Maven module path for "
                                + module
                                + ": "
                                + exception.getMessage());

                return false;
            }

            String moduleSelector =
                    relativeModule
                            .toString()
                            .replace(
                                    '\\',
                                    '/');

            if (moduleSelector.isBlank()
                    || ".".equals(moduleSelector)) {

                rootIsIncluded = true;
                continue;
            }

            try {

                List<String> command =
                        new ArrayList<>();

                command.add("mvn");

                command.add("-f");
                command.add(rootPom.toString());

                command.add("-pl");
                command.add(moduleSelector);

                command.add("-am");

                command.add("-DskipTests");

                command.add("-Dcheckstyle.skip=true");

                command.add("test-compile");

                MavenCommandRunner.run(
                        root,
                        command);

            } catch (IOException exception) {

                String message =
                        exception.getMessage();

                failures.add(
                        "Unable to compile test bytecode for Maven module "
                                + module
                                + " without running tests: "
                                + (message == null
                                || message.isBlank()
                                ? exception.getClass()
                                        .getSimpleName()
                                : message));

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

                String message =
                        exception.getMessage();

                failures.add(
                        "Unable to compile root-project test bytecode "
                                + "without running tests: "
                                + (message == null
                                || message.isBlank()
                                ? exception.getClass()
                                        .getSimpleName()
                                : message));

                return false;
            }
        }

        return true;
    }

    private static boolean hasJavaTestSources(
            Path sourceDirectory)
            throws IOException {

        try (var sources =
                     Files.walk(sourceDirectory)) {

            return sources.anyMatch(
                    path ->
                            Files.isRegularFile(path)
                                    && path.getFileName()
                                            .toString()
                                            .endsWith(".java"));
        }
    }

    private static boolean hasSourceNewerThan(
            Path sourceDirectory,
            Path newestClass)
            throws IOException {

        long classModified =
                Files.getLastModifiedTime(
                        newestClass)
                        .toMillis();

        try (var sources =
                     Files.walk(sourceDirectory)) {

            return sources
                    .filter(Files::isRegularFile)
                    .filter(
                            path ->
                                    path.getFileName()
                                            .toString()
                                            .endsWith(".java"))
                    .anyMatch(
                            path ->
                                    modifiedAfter(
                                            path,
                                            classModified));
        }
    }

    private static int compareModifiedTime(
            Path first,
            Path second) {

        try {

            return Files.getLastModifiedTime(first)
                    .compareTo(
                            Files.getLastModifiedTime(second));

        } catch (IOException exception) {

            return 0;
        }
    }

    private static boolean modifiedAfter(
            Path path,
            long timestamp) {

        try {

            return Files.getLastModifiedTime(
                            path)
                    .toMillis() > timestamp;

        } catch (IOException exception) {

            return false;
        }
    }

    /*
     * ============================================================
     * MAVEN INVOKER SUPPORT
     * ============================================================
     */

    private static void mapMavenInvokerFixtures(
            Path root,
            List<Path> modules,
            DependencyGraph graph,
            Set<String> affectedMethods,
            Map<String, Set<String>> testsByAffectedMethod,
            List<String> failures)
            throws IOException {

        Path invokerTests =
                root.resolve("src/it");

        if (!Files.isDirectory(
                invokerTests)
                || !isMavenPluginProject(
                        root.resolve("pom.xml"))) {

            return;
        }

        Path descriptor =
                root.resolve(
                        "target/classes/META-INF/maven/plugin.xml");

        if (!Files.isRegularFile(
                descriptor)) {

            return;
        }

        Map<String, InvokerGoal> goals =
                readPluginGoals(
                        descriptor);

        Map<String, String> classHierarchy =
                applicationClassHierarchy(
                        modules);

        Map<MethodKey, String> applicationMethods =
                applicationMethods(
                        graph);

        Map<String, Set<String>> calls =
                applicationCalls(
                        graph);

        try (var fixtureDirectories =
                     Files.list(invokerTests)) {

            for (Path fixture :
                    fixtureDirectories
                            .filter(Files::isDirectory)
                            .sorted()
                            .toList()) {

                Path fixturePom =
                        fixture.resolve(
                                "pom.xml");

                if (!Files.isRegularFile(
                        fixturePom)) {

                    continue;
                }

                List<InvokerGoal> fixtureGoals =
                        configuredInvokerGoals(
                                fixturePom,
                                root.resolve(
                                        "pom.xml"),
                                goals);

                if (fixtureGoals.isEmpty()) {
                    continue;
                }

                for (InvokerGoal goal :
                        fixtureGoals) {

                    String testCase =
                            "src/it/"
                                    + fixture.getFileName()
                                            .toString()
                                    + "#"
                                    + goal.name();

                    for (MethodKey entryPoint :
                            goalEntryPoints(
                                    goal,
                                    classHierarchy,
                                    applicationMethods)) {

                        Set<String> reached =
                                new LinkedHashSet<>();

                        traceApplicationMethod(
                                applicationMethods.get(
                                        entryPoint),
                                calls,
                                new LinkedHashSet<>(),
                                reached);

                        for (String affected :
                                affectedMethods) {

                            if (reached.contains(
                                    affected)) {

                                testsByAffectedMethod
                                        .computeIfAbsent(
                                                affected,
                                                ignored ->
                                                        new LinkedHashSet<>())
                                        .add(testCase);
                            }
                        }
                    }
                }
            }

        } catch (IOException exception) {

            failures.add(
                    "Unable to inspect Maven Invoker integration-test fixtures: "
                            + exception.getMessage());
        }
    }

    private static boolean isMavenPluginProject(
            Path pom)
            throws IOException {

        var document =
                readXml(pom);

        String packaging =
                directChildText(
                        document.getDocumentElement(),
                        "packaging");

        if ("maven-plugin".equals(
                packaging)) {

            return true;
        }

        for (Element pluginArtifact :
                descendants(
                        document.getDocumentElement(),
                        "artifactId")) {

            if ("maven-invoker-plugin".equals(
                    pluginArtifact
                            .getTextContent()
                            .trim())) {

                return true;
            }
        }

        return false;
    }

    private static Map<String, InvokerGoal> readPluginGoals(
            Path descriptor)
            throws IOException {

        var document =
                readXml(descriptor);

        Map<String, InvokerGoal> goals =
                new LinkedHashMap<>();

        List<Element> mojoElements =
                descendants(
                        document.getDocumentElement(),
                        "mojo");

        for (Element mojo :
                mojoElements) {

            String goal =
                    directChildText(
                            mojo,
                            "goal");

            String implementation =
                    directChildText(
                            mojo,
                            "implementation");

            boolean report =
                    "report".equals(goal)
                            || Boolean.parseBoolean(
                                    directChildText(
                                            mojo,
                                            "requiresReports"));

            if (!goal.isBlank()
                    && !implementation.isBlank()) {

                goals.put(
                        goal,
                        new InvokerGoal(
                                goal,
                                implementation.replace(
                                        '.',
                                        '/'),
                                report));
            }
        }

        return goals;
    }

    private static List<InvokerGoal> configuredInvokerGoals(
            Path fixturePom,
            Path projectPom,
            Map<String, InvokerGoal> descriptorGoals)
            throws IOException {

        var fixtureDocument =
                readXml(fixturePom);

        var projectDocument =
                readXml(projectPom);

        Element projectElement =
                projectDocument
                        .getDocumentElement();

        String groupId =
                directChildText(
                        projectElement,
                        "groupId");

        String artifactId =
                directChildText(
                        projectElement,
                        "artifactId");

        if (groupId.isBlank()) {

            Element parent =
                    directChild(
                            projectElement,
                            "parent");

            groupId =
                    parent == null
                            ? ""
                            : directChildText(
                                    parent,
                                    "groupId");
        }

        List<InvokerGoal> configuredGoals =
                new ArrayList<>();

        List<Element> plugins =
                descendants(
                        fixtureDocument
                                .getDocumentElement(),
                        "plugin");

        for (Element plugin :
                plugins) {

            String configuredGroup =
                    normalizePluginCoordinate(
                            directChildText(
                                    plugin,
                                    "groupId"),
                            groupId,
                            artifactId);

            String configuredArtifact =
                    normalizePluginCoordinate(
                            directChildText(
                                    plugin,
                                    "artifactId"),
                            groupId,
                            artifactId);

            if (!groupId.equals(
                    configuredGroup)
                    || !artifactId.equals(
                    configuredArtifact)) {

                continue;
            }

            if (hasAncestor(
                    plugin,
                    "reporting")) {

                List<String> reports =
                        descendantsText(
                                plugin,
                                "report");

                if (reports.isEmpty()) {

                    configuredGoals.addAll(
                            descriptorGoals.values()
                                    .stream()
                                    .filter(InvokerGoal::report)
                                    .filter(
                                            goal ->
                                                    !goal.name()
                                                            .equals(
                                                                    "aggregate"))
                                    .toList());

                } else {

                    for (String report :
                            reports) {

                        InvokerGoal goal =
                                descriptorGoals.get(
                                        report);

                        if (goal != null) {
                            configuredGoals.add(goal);
                        }
                    }
                }

            } else if (hasAncestor(
                    plugin,
                    "build")) {

                Element executions =
                        directChild(
                                plugin,
                                "executions");

                if (executions != null) {

                    for (Element executionGoal :
                            descendants(
                                    executions,
                                    "goal")) {

                        InvokerGoal goal =
                                descriptorGoals.get(
                                        executionGoal
                                                .getTextContent()
                                                .trim());

                        if (goal != null) {
                            configuredGoals.add(goal);
                        }
                    }
                }
            }
        }

        return configuredGoals
                .stream()
                .distinct()
                .toList();
    }

    private static String normalizePluginCoordinate(
            String coordinate,
            String groupId,
            String artifactId) {

        if (coordinate.equals(
                "@project.groupId@")
                || coordinate.equals(
                "${project.groupId}")) {

            return groupId;
        }

        if (coordinate.equals(
                "@project.artifactId@")
                || coordinate.equals(
                "${project.artifactId}")) {

            return artifactId;
        }

        return coordinate;
    }

    private static List<MethodKey> goalEntryPoints(
            InvokerGoal goal,
            Map<String, String> classHierarchy,
            Map<MethodKey, String> applicationMethods) {

        Set<String> owners =
                new LinkedHashSet<>();

        String owner =
                goal.implementation();

        while (owner != null
                && !owner.isBlank()
                && owners.add(owner)) {

            owner =
                    classHierarchy.get(owner);
        }

        String expectedMethod =
                goal.report()
                        ? "executeReport"
                        : "execute";

        return applicationMethods.keySet()
                .stream()
                .filter(
                        method ->
                                owners.contains(
                                        method.owner()))
                .filter(
                        method ->
                                method.name()
                                        .equals(
                                                expectedMethod))
                .toList();
    }

    private static Map<String, String> applicationClassHierarchy(
            List<Path> modules)
            throws IOException {

        Map<String, String> hierarchy =
                new LinkedHashMap<>();

        for (Path module :
                modules) {

            Path classes =
                    module.resolve(
                            "target/classes");

            if (!Files.isDirectory(
                    classes)) {

                continue;
            }

            try (var files =
                         Files.walk(classes)) {

                for (Path classFile :
                        files
                                .filter(Files::isRegularFile)
                                .filter(
                                        path ->
                                                path.getFileName()
                                                        .toString()
                                                        .endsWith(".class"))
                                .toList()) {

                    try (InputStream input =
                                 Files.newInputStream(
                                         classFile)) {

                        new ClassReader(input)
                                .accept(
                                        new ClassVisitor(
                                                Opcodes.ASM9) {

                                            @Override
                                            public void visit(
                                                    int version,
                                                    int access,
                                                    String name,
                                                    String signature,
                                                    String superName,
                                                    String[] interfaces) {

                                                hierarchy.put(
                                                        name,
                                                        superName);
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

    /*
     * ============================================================
     * XML
     * ============================================================
     */

    private static org.w3c.dom.Document readXml(
            Path file)
            throws IOException {

        try {

            return secureDocumentBuilderFactory()
                    .newDocumentBuilder()
                    .parse(file.toFile());

        } catch (ParserConfigurationException
                 | SAXException exception) {

            throw new IOException(
                    "Unable to read XML file "
                            + file
                            + ": "
                            + exception.getMessage(),
                    exception);
        }
    }

    private static Element directChild(
            Element parent,
            String name) {

        for (Node child =
                     parent.getFirstChild();
             child != null;
             child = child.getNextSibling()) {

            if (child instanceof Element element
                    && localName(element)
                    .equals(name)) {

                return element;
            }
        }

        return null;
    }

    private static String directChildText(
            Element parent,
            String name) {

        Element child =
                directChild(
                        parent,
                        name);

        return child == null
                ? ""
                : child.getTextContent()
                        .trim();
    }

    private static String localName(
            Element element) {

        return element.getLocalName() == null
                ? element.getTagName()
                : element.getLocalName();
    }

    private static boolean hasAncestor(
            Element element,
            String name) {

        for (Node parent =
                     element.getParentNode();
             parent instanceof Element;
             parent = parent.getParentNode()) {

            if (localName((Element) parent)
                    .equals(name)) {

                return true;
            }
        }

        return false;
    }

    private static List<String> descendantsText(
            Element element,
            String name) {

        List<Element> descendants =
                descendants(
                        element,
                        name);

        List<String> texts =
                new ArrayList<>(
                        descendants.size());

        for (Element descendant :
                descendants) {

            String text =
                    descendant
                            .getTextContent()
                            .trim();

            if (!text.isEmpty()) {
                texts.add(text);
            }
        }

        return texts;
    }

    private static List<Element> descendants(
            Element parent,
            String name) {

        List<Element> matches =
                new ArrayList<>();

        for (Node child =
                     parent.getFirstChild();
             child != null;
             child = child.getNextSibling()) {

            if (child instanceof Element element) {

                if (localName(element)
                        .equals(name)) {

                    matches.add(element);
                }

                matches.addAll(
                        descendants(
                                element,
                                name));
            }
        }

        return matches;
    }

    private record InvokerGoal(
            String name,
            String implementation,
            boolean report) {
    }

    /*
     * ============================================================
     * LEGACY COVERAGE STATUS
     * ============================================================
     */

    private static CoverageResult legacyCoverageStatus(
            List<Path> modules,
            List<String> affectedMethods,
            List<String> directlyImpactedMethods,
            List<String> indirectlyAffectedMethods)
            throws IOException {

        List<String> failures =
                new ArrayList<>();

        Map<String,
                DependencyGraphResult.TestCoverageStatus> testCases =
                new LinkedHashMap<>();

        boolean foundCoverageReport =
                false;

        boolean foundTestReport =
                false;

        for (Path module :
                modules) {

            Path targetDirectory =
                    module.resolve("target");

            if (!Files.isDirectory(
                    targetDirectory)) {

                continue;
            }

            foundCoverageReport |=
                    !findCoverageReports(
                            targetDirectory)
                            .isEmpty();

            for (Path report :
                    findTestReports(
                            targetDirectory)) {

                foundTestReport = true;

                for (DependencyGraphResult.TestCoverageStatus test :
                        readTestCases(report)) {

                    testCases.putIfAbsent(
                            test.testClass()
                                    + "#"
                                    + test.testMethod(),
                            test);
                }
            }
        }

        if (!foundCoverageReport) {

            failures.add(
                    "No JaCoCo XML coverage report was found "
                            + "in the Maven modules.");
        }

        if (!foundTestReport) {

            failures.add(
                    "No Surefire or Failsafe XML test report "
                            + "was found in the Maven modules.");
        }

        List<DependencyGraphResult.AffectedMethodTestGroup> groups =
                new ArrayList<>();

        for (String method :
                new LinkedHashSet<>(affectedMethods)) {

            groups.add(
                    new DependencyGraphResult.AffectedMethodTestGroup(
                            method,
                            impactTypeFor(
                                    method,
                                    directlyImpactedMethods,
                                    indirectlyAffectedMethods),
                            List.of(),
                            "N/A",
                            TEST_SELECTION_UNAVAILABLE));
        }

        return new CoverageResult(
                List.of(),
                List.of(),
                groups,
                failures,
                new ArrayList<>(
                        testCases.values()),
                TEST_SELECTION_UNAVAILABLE);
    }

    /*
     * ============================================================
     * TEST BYTECODE READING
     * ============================================================
     */

    private static void readTestBytecode(
            Path testClasses,
            Map<MethodKey, Set<MethodKey>> calls,
            Set<MethodKey> testEntryPoints)
            throws IOException {

        try (var files =
                     Files.walk(testClasses)) {

            for (Path classFile :
                    files
                            .filter(Files::isRegularFile)
                            .filter(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                                                    .endsWith(".class"))
                            .toList()) {

                try (InputStream input =
                             Files.newInputStream(
                                     classFile)) {

                    new ClassReader(input)
                            .accept(
                                    new ClassVisitor(
                                            Opcodes.ASM9) {

                                        private String className;

                                        @Override
                                        public void visit(
                                                int version,
                                                int access,
                                                String name,
                                                String signature,
                                                String superName,
                                                String[] interfaces) {

                                            className = name;
                                        }

                                        @Override
                                        public MethodVisitor visitMethod(
                                                int access,
                                                String name,
                                                String descriptor,
                                                String signature,
                                                String[] exceptions) {

                                            MethodKey method =
                                                    new MethodKey(
                                                            className,
                                                            name,
                                                            descriptor);

                                            Set<MethodKey> invokedMethods =
                                                    calls.computeIfAbsent(
                                                            method,
                                                            ignored ->
                                                                    new LinkedHashSet<>());

                                            return new MethodVisitor(
                                                    Opcodes.ASM9) {

                                                @Override
                                                public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                                        String annotationDescriptor,
                                                        boolean visible) {

                                                    if (isTestAnnotation(
                                                            annotationDescriptor)) {

                                                        testEntryPoints
                                                                .add(method);
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

                                                    invokedMethods.add(
                                                            new MethodKey(
                                                                    owner,
                                                                    methodName,
                                                                    methodDescriptor));
                                                }
                                            };
                                        }
                                    },
                                    ClassReader.SKIP_DEBUG
                                            | ClassReader.SKIP_FRAMES);
                }
            }
        }
    }

    private static boolean isTestAnnotation(
            String descriptor) {

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

    /*
     * ============================================================
     * APPLICATION GRAPH
     * ============================================================
     */

    private static Map<MethodKey, String> applicationMethods(
            DependencyGraph graph) {

        Map<MethodKey, String> methods =
                new LinkedHashMap<>();

        if (graph == null) {
            return methods;
        }

        for (GraphNode node :
                graph.getNodes()) {

            if (node.type()
                    != GraphNode.Type.METHOD) {

                continue;
            }

            Object className =
                    node.metadata()
                            .get("className");

            Object methodName =
                    node.metadata()
                            .get("methodName");

            Object descriptor =
                    node.metadata()
                            .get("descriptor");

            if (className instanceof String owner
                    && methodName instanceof String name
                    && descriptor instanceof String methodDescriptor) {

                methods.put(
                        new MethodKey(
                                owner.replace(
                                        '.',
                                        '/'),
                                name,
                                methodDescriptor),
                        node.id());
            }
        }

        return methods;
    }

    private static Map<String, Set<String>> applicationCalls(
            DependencyGraph graph) {

        Map<String, Set<String>> calls =
                new LinkedHashMap<>();

        if (graph == null) {
            return calls;
        }

        for (GraphEdge edge :
                graph.getEdges()) {

            if ("CALLS".equals(
                    edge.type())) {

                calls.computeIfAbsent(
                        edge.source(),
                        ignored ->
                                new LinkedHashSet<>())
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

        if (!visitedTestMethods.add(
                method)) {

            return;
        }

        String applicationMethod =
                applicationMethods.get(
                        method);

        if (applicationMethod != null) {

            traceApplicationMethod(
                    applicationMethod,
                    applicationCalls,
                    new LinkedHashSet<>(),
                    reachedApplicationMethods);

            return;
        }

        for (MethodKey invokedMethod :
                testCalls.getOrDefault(
                        method,
                        Set.of())) {

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

        if (!visited.add(
                method)) {

            return;
        }

        reachedApplicationMethods.add(
                method);

        for (String callee :
                applicationCalls.getOrDefault(
                        method,
                        Set.of())) {

            traceApplicationMethod(
                    callee,
                    applicationCalls,
                    visited,
                    reachedApplicationMethods);
        }
    }

    private record MethodKey(
            String owner,
            String name,
            String descriptor) {
    }

    /*
     * ============================================================
     * MAVEN MODULE DISCOVERY
     * ============================================================
     */

    private static List<Path> discoverModules(
            Path root)
            throws IOException {

        Path rootPom =
                root.resolve("pom.xml");

        if (!Files.isRegularFile(
                rootPom)) {

            throw new IOException(
                    "No Maven pom.xml file was found at project root: "
                            + root);
        }

        Set<Path> modules =
                new LinkedHashSet<>();

        ArrayDeque<Path> pending =
                new ArrayDeque<>();

        pending.add(root);

        while (!pending.isEmpty()) {

            Path moduleRoot =
                    pending.removeFirst();

            if (!modules.add(
                    moduleRoot)) {

                continue;
            }

            Path pom =
                    moduleRoot.resolve(
                            "pom.xml");

            for (String modulePath :
                    readModulePaths(pom)) {

                Path child =
                        moduleRoot
                                .resolve(modulePath)
                                .normalize();

                if (child.startsWith(root)
                        && Files.isRegularFile(
                                child.resolve(
                                        "pom.xml"))) {

                    pending.addLast(child);
                }
            }
        }

        return modules.stream()
                .sorted()
                .toList();
    }

    private static List<String> readModulePaths(
            Path pom)
            throws IOException {

        try {

            var document =
                    secureDocumentBuilderFactory()
                            .newDocumentBuilder()
                            .parse(pom.toFile());

            List<Element> moduleElements =
                    descendants(
                            document.getDocumentElement(),
                            "module");

            List<String> modulePaths =
                    new ArrayList<>(
                            moduleElements.size());

            for (Element moduleElement :
                    moduleElements) {

                String modulePath =
                        moduleElement
                                .getTextContent()
                                .trim();

                if (!modulePath.isEmpty()) {
                    modulePaths.add(modulePath);
                }
            }

            return modulePaths;

        } catch (ParserConfigurationException
                 | SAXException exception) {

            throw new IOException(
                    "Unable to read Maven module declarations from "
                            + pom
                            + ": "
                            + exception.getMessage(),
                    exception);
        }
    }

    /*
     * ============================================================
     * TEST REPORTS
     * ============================================================
     */

    private static List<DependencyGraphResult.TestCoverageStatus> readTestCases(
            Path report)
            throws IOException {

        try {

            var document =
                    secureDocumentBuilderFactory()
                            .newDocumentBuilder()
                            .parse(report.toFile());

            List<Element> testCases =
                    descendants(
                            document.getDocumentElement(),
                            "testcase");

            List<DependencyGraphResult.TestCoverageStatus> tests =
                    new ArrayList<>(
                            testCases.size());

            for (Element testCase :
                    testCases) {

                String testClass =
                        testCase
                                .getAttribute(
                                        "classname")
                                .trim();

                String testMethod =
                        testCase
                                .getAttribute(
                                        "name")
                                .trim();

                if (!testClass.isEmpty()
                        && !testMethod.isEmpty()) {

                    tests.add(
                            new DependencyGraphResult.TestCoverageStatus(
                                    testClass,
                                    testMethod,
                                    "N/A",
                                    TEST_SELECTION_UNAVAILABLE));
                }
            }

            return tests;

        } catch (ParserConfigurationException
                 | SAXException exception) {

            throw new IOException(
                    "Unable to read test report "
                            + report
                            + ": "
                            + exception.getMessage(),
                    exception);
        }
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory()
            throws ParserConfigurationException {

        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();

        factory.setNamespaceAware(true);

        factory.setFeature(
                XMLConstants.FEATURE_SECURE_PROCESSING,
                true);

        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true);

        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false);

        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false);

        factory.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_DTD,
                "");

        factory.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_SCHEMA,
                "");

        return factory;
    }

    private static List<Path> findCoverageReports(
            Path targetDirectory)
            throws IOException {

        try (var paths =
                     Files.walk(
                             targetDirectory)) {

            return paths
                    .filter(Files::isRegularFile)
                    .filter(
                            path -> {

                                String name =
                                        path.getFileName()
                                                .toString();

                                return name.startsWith(
                                        "jacoco")
                                        && name.endsWith(
                                        ".xml");
                            })
                    .toList();
        }
    }

    private static List<Path> findTestReports(
            Path targetDirectory)
            throws IOException {

        try (var paths =
                     Files.walk(
                             targetDirectory)) {

            return paths
                    .filter(Files::isRegularFile)
                    .filter(
                            path -> {

                                Path parent =
                                        path.getParent();

                                if (parent == null) {
                                    return false;
                                }

                                String reportDirectory =
                                        parent.getFileName()
                                                .toString();

                                String fileName =
                                        path.getFileName()
                                                .toString();

                                return (
                                        reportDirectory.equals(
                                                "surefire-reports")
                                                || reportDirectory.equals(
                                                "failsafe-reports"))
                                        && fileName.startsWith(
                                        "TEST-")
                                        && fileName.endsWith(
                                        ".xml");
                            })
                    .toList();
        }
    }

    /*
     * ============================================================
     * IMPACT TYPE
     * ============================================================
     */

    private static String impactTypeFor(
            String method,
            List<String> directlyImpactedMethods,
            List<String> indirectlyAffectedMethods) {

        if (directlyImpactedMethods != null
                && directlyImpactedMethods.contains(
                method)) {

            return "DIRECT";
        }

        if (indirectlyAffectedMethods != null
                && indirectlyAffectedMethods.contains(
                method)) {

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

        List<DependencyGraphResult.AffectedMethodTestGroup> groups =
                new ArrayList<>();

        for (String method :
                new LinkedHashSet<>(affectedMethods)) {

            groups.add(
                    new DependencyGraphResult.AffectedMethodTestGroup(
                            method,
                            impactTypeFor(
                                    method,
                                    directlyImpactedMethods,
                                    indirectlyAffectedMethods),
                            List.of(),
                            "NOT ANALYZED",
                            reason));
        }

        return groups;
    }

    /*
     * ============================================================
     * RESULT
     * ============================================================
     */

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

            this(
                    methodTestCoverage,
                    selectedTests,
                    groupedSelectedTests,
                    failures,
                    List.of(),
                    selectionNote);
        }

        public CoverageResult {

            methodTestCoverage =
                    methodTestCoverage == null
                            ? List.of()
                            : List.copyOf(
                            methodTestCoverage);

            selectedTests =
                    selectedTests == null
                            ? List.of()
                            : List.copyOf(
                            selectedTests);

            groupedSelectedTests =
                    groupedSelectedTests == null
                            ? List.of()
                            : List.copyOf(
                            groupedSelectedTests);

            failures =
                    failures == null
                            ? List.of()
                            : List.copyOf(
                            failures);

            testCoverageStatuses =
                    testCoverageStatuses == null
                            ? List.of()
                            : List.copyOf(
                            testCoverageStatuses);

            selectionNote =
                    selectionNote == null
                            ? ""
                            : selectionNote;
        }
    }
}