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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ApplicationModuleScanner {

    private final MavenModuleClasspathResolver outputResolver;
    private final List<String> diagnostics;

    ApplicationModuleScanner(MavenModuleClasspathResolver outputResolver) {
        this(outputResolver, new ArrayList<>());
    }

    ApplicationModuleScanner(MavenModuleClasspathResolver outputResolver, List<String> diagnostics) {
        this.outputResolver = outputResolver;
        this.diagnostics = diagnostics;
    }

    List<ApplicationModule> discover(Path projectDirectory) throws IOException {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();

        if (!Files.isDirectory(projectRoot)) {
            throw new IOException(
                    "Project directory does not exist: " + projectRoot);
        }

        Map<Path, Path> discoveredPoms = discoverPoms(projectRoot);
        Set<Path> reactorDirectories = discoverReactorDirectories(
            projectRoot, discoveredPoms, diagnostics);

        List<ApplicationModule> modules = new ArrayList<>();

        for (Path moduleDirectory : reactorDirectories) {
            Path pom = discoveredPoms.get(moduleDirectory);

            if (pom == null || !Files.isRegularFile(pom)) {
                continue;
            }

            boolean hasMainSources = hasMainSources(moduleDirectory);
            boolean hasConfiguredSources =
                    hasSourceDirectoryConfiguration(moduleDirectory, projectRoot);
            boolean hasApplicationSources =
                    hasMainSources || hasConfiguredSources;
            boolean hasDefaultOutput =
                    Files.isDirectory(moduleDirectory.resolve("target/classes"));
            boolean hasBuildConfiguration =
                    hasBuildDiscoveryConfiguration(moduleDirectory, projectRoot);

            if (!hasApplicationSources && !hasDefaultOutput
                    && !hasBuildConfiguration) {
                continue;
            }

            ApplicationModule unresolved = new ApplicationModule(
                    projectRoot,
                    moduleDirectory,
                    pom,
                    List.of(),
                    hasApplicationSources);

            List<Path> classesDirectories;

            try {
                classesDirectories =
                        outputResolver.resolveOutputDirectories(unresolved);
            } catch (IOException | RuntimeException exception) {
                /*
                 * Isolate output discovery failure to this module.
                 * Preserve the conventional output location as a fallback.
                 * The bytecode analyzer will determine whether the output
                 * is actually available before claiming an impact result.
                 */
                Path conventionalOutput =
                        moduleDirectory.resolve("target/classes");

                diagnostics.add("Output discovery unavailable for module " + moduleDirectory
                    + ": " + messageOf(exception));

                if (Files.isDirectory(conventionalOutput)) {
                    classesDirectories = List.of(conventionalOutput);
                } else if (hasApplicationSources) {
                    classesDirectories = List.of(conventionalOutput);
                } else {
                    continue;
                }
            }

            if (classesDirectories == null) {
                classesDirectories = List.of();
            }

            List<Path> normalizedDirectories = classesDirectories.stream()
                    .filter(path -> path != null)
                    .map(path -> path.isAbsolute()
                            ? path.normalize()
                            : moduleDirectory.resolve(path).normalize())
                    .distinct()
                    .toList();

            boolean hasExistingOutput = normalizedDirectories.stream()
                    .anyMatch(Files::isDirectory);

            if (hasApplicationSources || hasExistingOutput) {
                modules.add(new ApplicationModule(
                        projectRoot,
                        moduleDirectory,
                        pom,
                        normalizedDirectories,
                        hasApplicationSources));
            }
        }

        modules.sort(Comparator.comparing(
                module -> module.moduleDirectory().toString()));

        if (modules.isEmpty()) {
                diagnostics.add("No application modules with source roots or compiled output were discovered under "
                    + projectRoot + ".");
        }

        return List.copyOf(modules);
    }

    private static Map<Path, Path> discoverPoms(Path projectRoot)
            throws IOException {

        Map<Path, Path> poms = new LinkedHashMap<>();

        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes) {

                if (!directory.equals(projectRoot)) {
                    Path name = directory.getFileName();

                    if (name != null
                            && (name.toString().equals(".git")
                            || name.toString().equals("node_modules")
                            || name.toString().equals("target"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }

                Path pom = directory.resolve("pom.xml");

                if (Files.isRegularFile(pom)) {
                    poms.put(directory.toAbsolutePath().normalize(), pom);
                }

                return FileVisitResult.CONTINUE;
            }
        });

        return poms;
    }

            private static Set<Path> discoverReactorDirectories(
            Path projectRoot,
                Map<Path, Path> discoveredPoms,
                List<String> diagnostics) throws IOException {

        Set<Path> reactor = new LinkedHashSet<>();
        Set<Path> visited = new LinkedHashSet<>();

        Path rootPom = discoveredPoms.get(projectRoot);

        if (rootPom == null) {
            /*
             * A project without a root pom.xml may still contain a Maven
             * project at its root or in a subdirectory. Retain discovered
             * POMs in this fallback case rather than silently losing them.
             */
            reactor.addAll(discoveredPoms.keySet());
            return reactor;
        }

        if (readDeclaredModules(rootPom, diagnostics).isEmpty()) {
            reactor.addAll(discoveredPoms.keySet());
        } else {
            collectReactorModules(
                    projectRoot,
                    projectRoot,
                    discoveredPoms,
                    reactor,
                    visited,
                    diagnostics);
        }

        return reactor;
    }

    private static void collectReactorModules(
            Path projectRoot,
            Path moduleDirectory,
            Map<Path, Path> discoveredPoms,
            Set<Path> reactor,
            Set<Path> visited,
            List<String> diagnostics) {

        Path normalizedDirectory =
                moduleDirectory.toAbsolutePath().normalize();

        if (!normalizedDirectory.startsWith(projectRoot)
                || !visited.add(normalizedDirectory)) {
            return;
        }

        Path pom = discoveredPoms.get(normalizedDirectory);

        if (pom == null) {
            return;
        }

        reactor.add(normalizedDirectory);

        try {
            for (String declaredModule : readDeclaredModules(pom, diagnostics)) {
                Path childDirectory = normalizedDirectory
                        .resolve(declaredModule)
                        .normalize()
                        .toAbsolutePath();

                if (!childDirectory.startsWith(projectRoot)) {
                        diagnostics.add("Maven module path is outside the project root and was not scanned: "
                            + childDirectory);
                    continue;
                }

                if (!discoveredPoms.containsKey(childDirectory)) {
                        diagnostics.add("Declared Maven module has no pom.xml: " + childDirectory);
                    continue;
                }

                collectReactorModules(
                        projectRoot,
                        childDirectory,
                        discoveredPoms,
                        reactor,
                        visited,
                        diagnostics);
            }
        } catch (IOException exception) {
                diagnostics.add("Could not read module declarations from " + pom + ": "
                    + messageOf(exception));
        }
    }

    private static List<String> readDeclaredModules(Path pom, List<String> diagnostics)
            throws IOException {

        /*
         * Read direct <modules><module> declarations from the POM.
         * XML parsing is used instead of string matching so comments and
         * unrelated XML elements do not become module paths.
         */
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();

            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true);
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities",
                    false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            org.w3c.dom.Document document =
                    factory.newDocumentBuilder().parse(pom.toFile());

            org.w3c.dom.Element project =
                    document.getDocumentElement();

            List<String> modules = new ArrayList<>();

            org.w3c.dom.NodeList children = project.getChildNodes();

            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node child = children.item(i);

                if (child.getNodeType()
                        != org.w3c.dom.Node.ELEMENT_NODE) {
                    continue;
                }

                if (!"modules".equals(child.getLocalName())
                        && !"modules".equals(child.getNodeName())) {
                    continue;
                }

                org.w3c.dom.NodeList entries = child.getChildNodes();

                for (int j = 0; j < entries.getLength(); j++) {
                    org.w3c.dom.Node entry = entries.item(j);

                    if (entry.getNodeType()
                            == org.w3c.dom.Node.ELEMENT_NODE
                            && ("module".equals(entry.getLocalName())
                            || "module".equals(entry.getNodeName()))) {

                        String value = entry.getTextContent().trim();

                        if (!value.isBlank()
                                && !value.contains("${")) {
                            modules.add(value);
                        } else if (value.contains("${")) {
                                diagnostics.add("Module path uses an unresolved Maven property in "
                                    + pom + ": " + value);
                        }
                    }
                }
            }

            return modules;
        } catch (javax.xml.parsers.ParserConfigurationException
                 | org.xml.sax.SAXException exception) {
            throw new IOException(
                    "Could not parse Maven module declarations in " + pom,
                    exception);
        }
    }

    private static boolean hasMainSources(Path moduleDirectory) {
        return Files.isDirectory(moduleDirectory.resolve("src/main/java"))
                || Files.isDirectory(moduleDirectory.resolve("src/main/kotlin"))
                || Files.isDirectory(moduleDirectory.resolve("src/main/scala"));
    }

    private static boolean hasBuildDiscoveryConfiguration(
            Path moduleDirectory,
            Path projectRoot) throws IOException {

        for (Path directory = moduleDirectory;
             directory != null && directory.startsWith(projectRoot);
             directory = directory.getParent()) {

            Path pom = directory.resolve("pom.xml");

            if (!Files.isRegularFile(pom)) {
                continue;
            }

            String contents = Files.readString(pom);

            if (contents.contains("<outputDirectory")
                    || contents.contains("<directory>")
                    || contents.contains("<sourceDirectory")) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasSourceDirectoryConfiguration(
            Path moduleDirectory,
            Path projectRoot) throws IOException {

        for (Path directory = moduleDirectory;
             directory != null && directory.startsWith(projectRoot);
             directory = directory.getParent()) {

            Path pom = directory.resolve("pom.xml");

            if (Files.isRegularFile(pom)
                    && Files.readString(pom).contains("<sourceDirectory")) {
                return true;
            }
        }

        return false;
    }

    private static String messageOf(Throwable exception) {
        String message = exception.getMessage();

        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}