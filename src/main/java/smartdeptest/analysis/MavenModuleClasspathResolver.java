package smartdeptest.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class MavenModuleClasspathResolver {
    private final MavenArtifactResolver.MavenInvoker mavenInvoker;
    private final Map<Path, List<Path>> outputDirectories =
            new java.util.HashMap<>();

    MavenModuleClasspathResolver() {
        this(MavenCommandRunner::run);
    }

    MavenModuleClasspathResolver(
            MavenArtifactResolver.MavenInvoker mavenInvoker) {
        this.mavenInvoker = mavenInvoker;
    }

    List<Path> resolveOutputDirectories(
            ApplicationModule module) throws IOException {

        Path pom = module.pomFile()
                .toAbsolutePath()
                .normalize();

        List<Path> cached = outputDirectories.get(pom);

        if (cached != null) {
            return cached;
        }

        Path effectivePom =
                Files.createTempFile(
                        "smartdeptest-effective-pom-",
                        ".xml");

        try {
            Files.deleteIfExists(effectivePom);

            List<String> command = List.of(
                    "mvn",
                    "-f",
                    pom.toString(),
                    "help:effective-pom",
                    "-Doutput=" + effectivePom);

            MavenArtifactResolver.runWithCachedMissRetry(
                    mavenInvoker,
                    module.projectDirectory(),
                    command);

            if (!Files.isRegularFile(effectivePom)) {
                throw new IOException(
                        "Maven completed without writing its effective POM for "
                                + pom);
            }

            List<Path> resolved =
                    parseOutputDirectories(
                            effectivePom,
                            module.moduleDirectory());

            if (resolved.isEmpty()) {
                throw new IOException(
                        "Maven effective POM has no build output directory: "
                                + pom);
            }

            outputDirectories.put(pom, resolved);
            return resolved;

        } catch (IOException exception) {

            Path defaultOutput =
                    module.moduleDirectory()
                            .resolve("target/classes")
                            .toAbsolutePath()
                            .normalize();

            if (Files.isDirectory(defaultOutput)) {
                List<Path> fallback =
                        List.of(defaultOutput);

                outputDirectories.put(
                        pom,
                        fallback);

                return fallback;
            }

            throw new IOException(
                    "Unable to determine compiled application output directory from Maven POM "
                            + pom
                            + ". Compile the module or fix its effective Maven model. "
                            + exception.getMessage(),
                    exception);

        } finally {
            Files.deleteIfExists(effectivePom);
        }
    }

    List<Path> resolve(
            ApplicationModule module) throws IOException {

        Path output =
                Files.createTempFile(
                        "smartdeptest-classpath-",
                        ".txt");

        boolean usedReactor = false;

        try {
            Files.deleteIfExists(output);

            IOException reactorFailure = null;

            Path reactorPom =
                    module.projectDirectory()
                            .resolve("pom.xml")
                            .toAbsolutePath()
                            .normalize();

            Path modulePom =
                    module.pomFile()
                            .toAbsolutePath()
                            .normalize();

            /*
             * If the analyzed module is inside a Maven reactor,
             * try the root reactor first.
             */
            if (!reactorPom.equals(modulePom)
                    && Files.isRegularFile(reactorPom)) {

                String moduleSelector =
                        calculateModuleSelector(
                                module.projectDirectory(),
                                module.moduleDirectory());

                List<String> reactorSelection =
                        buildReactorSelection(
                                moduleSelector);

                try {
                    System.out.println(
                            "Resolving Maven classpath through root reactor: "
                                    + reactorPom
                                    + " (module "
                                    + moduleSelector
                                    + ")...");

                    /*
                     * First attempt:
                     * normal dependency:build-classpath.
                     */
                    runBuildClasspath(
                            module.projectDirectory(),
                            reactorPom,
                            output,
                            reactorSelection);

                    usedReactor = true;

                } catch (IOException exception) {

                    reactorFailure = exception;

                    System.out.println(
                            "Root reactor classpath resolution failed: "
                                    + summarize(exception));

                    Files.deleteIfExists(output);

                    /*
                     * If the root POM is not actually a usable
                     * Maven reactor, immediately fall back to
                     * the standalone module POM.
                     *
                     * This is important because trying
                     * prepareReactor() here would create an
                     * unnecessary additional Maven invocation.
                     */
                    if (isNotAReactorFailure(exception)) {

                        System.out.println(
                                "Root POM is not a usable Maven reactor; "
                                        + "falling back to standalone module POM.");

                    } else {

                        /*
                         * The root reactor exists and the failure
                         * may be recoverable by preparing the
                         * selected module and its upstream modules.
                         */
                        try {

                            System.out.println(
                                    "Preparing required Maven reactor modules...");

                            prepareReactor(
                                    module.projectDirectory(),
                                    reactorPom,
                                    reactorSelection);

                            Files.deleteIfExists(output);

                            /*
                             * Retry using the exact same
                             * -pl/-am selection.
                             */
                            runBuildClasspath(
                                    module.projectDirectory(),
                                    reactorPom,
                                    output,
                                    reactorSelection);

                            usedReactor = true;
                            reactorFailure = null;

                        } catch (IOException preparationFailure) {

                            preparationFailure.addSuppressed(
                                    exception);

                            reactorFailure =
                                    preparationFailure;

                            System.out.println(
                                    "Maven reactor preparation failed: "
                                            + summarize(
                                                    preparationFailure));

                            Files.deleteIfExists(output);
                        }
                    }
                }
            }

            /*
             * Standalone fallback.
             *
             * This is also used when:
             * - the root reactor does not exist,
             * - the root POM is the module POM,
             * - reactor resolution fails,
             * - the root POM is not a usable Maven reactor.
             */
            if (!Files.isRegularFile(output)) {

                try {

                    runBuildClasspath(
                            module.projectDirectory(),
                            module.pomFile(),
                            output,
                            List.of());

                } catch (IOException standaloneFailure) {

                    if (reactorFailure != null) {
                        standaloneFailure.addSuppressed(
                                reactorFailure);
                    }

                    String reactorError =
                            reactorFailure == null
                                    ? "not attempted"
                                    : summarize(
                                            reactorFailure);

                    throw new IOException(
                            "Unable to resolve Maven compile classpath for module POM "
                                    + module.pomFile()
                                    + ". Root reactor attempt: "
                                    + reactorError
                                    + ". Standalone attempt: "
                                    + summarize(
                                            standaloneFailure),
                            standaloneFailure);
                }
            }

            List<Path> entries =
                    new ArrayList<>();

            if (Files.isRegularFile(output)) {

                String classpath =
                        Files.readString(output).trim();

                if (!classpath.isBlank()) {

                    for (String entry :
                            classpath.split(
                                    java.util.regex.Pattern.quote(
                                            java.io.File.pathSeparator))) {

                        if (entry == null
                                || entry.isBlank()) {
                            continue;
                        }

                        try {

                            Path path =
                                    Path.of(entry);

                            if (Files.exists(path)) {
                                entries.add(
                                        path.toAbsolutePath()
                                                .normalize());
                            }

                        } catch (RuntimeException ignored) {
                            /*
                             * Ignore malformed classpath entries.
                             */
                        }
                    }
                }
            }

            /*
             * Always include application compiled class
             * directories when Maven reactor resolution was
             * successfully used.
             *
             * For standalone fallback, the Maven-generated
             * classpath is used as-is. This preserves the
             * expected standalone resolver behavior.
             */
            if (usedReactor) {

                module.classesDirectories()
                        .stream()
                        .filter(Files::isDirectory)
                        .map(path ->
                                path.toAbsolutePath()
                                        .normalize())
                        .forEach(entries::add);
            }

            return entries.stream()
                    .distinct()
                    .toList();

        } finally {
            Files.deleteIfExists(output);
        }
    }

    private void runBuildClasspath(
            Path workingDirectory,
            Path pomFile,
            Path output,
            List<String> selectionArguments)
            throws IOException {

        List<String> command =
                new ArrayList<>();

        command.add("mvn");
        command.add("-f");
        command.add(pomFile.toString());

        command.addAll(selectionArguments);

        command.add(
                "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:build-classpath");

        command.add("-DincludeScope=compile");

        command.add(
                "-Dmdep.outputFile="
                        + output);

        MavenArtifactResolver.runWithCachedMissRetry(
                mavenInvoker,
                workingDirectory,
                command);

        if (!Files.isRegularFile(output)) {
            throw new IOException(
                    "Maven completed without writing the requested classpath file: "
                            + output);
        }
    }

    private void prepareReactor(
            Path workingDirectory,
            Path reactorPom,
            List<String> reactorSelection)
            throws IOException {

        List<String> command =
                new ArrayList<>();

        command.add("mvn");
        command.add("-f");
        command.add(reactorPom.toString());

        command.addAll(reactorSelection);

        command.add("package");
        command.add("-DskipTests");

        MavenArtifactResolver.runWithCachedMissRetry(
                mavenInvoker,
                workingDirectory,
                command);
    }

    private static String calculateModuleSelector(
            Path projectDirectory,
            Path moduleDirectory)
            throws IOException {

        Path root =
                projectDirectory
                        .toAbsolutePath()
                        .normalize();

        Path module =
                moduleDirectory
                        .toAbsolutePath()
                        .normalize();

        if (!module.startsWith(root)) {
            throw new IOException(
                    "Application module directory "
                            + module
                            + " is outside Maven reactor root "
                            + root);
        }

        Path relative =
                root.relativize(module);

        if (relative.getNameCount() == 0) {
            return ".";
        }

        return relative.toString()
                .replace('\\', '/');
    }

    private static List<String> buildReactorSelection(
            String moduleSelector) {

        if (moduleSelector == null
                || moduleSelector.isBlank()
                || ".".equals(moduleSelector)) {

            return List.of();
        }

        return List.of(
                "-pl",
                moduleSelector,
                "-am");
    }

    /*
     * Determines whether the reactor failure indicates that
     * the root POM is not actually a usable Maven reactor.
     */
    private static boolean isNotAReactorFailure(
            IOException exception) {

        String message =
                exception.getMessage();

        if (message == null
                || message.isBlank()) {
            return false;
        }

        String normalized =
                message.toLowerCase(
                        Locale.ROOT);

        return normalized.contains(
                    "not a maven reactor")
                || normalized.contains(
                    "could not find the selected project in the reactor")
                || normalized.contains(
                    "could not select module")
                || normalized.contains(
                    "project is not part of the reactor");
    }

    /*
     * THIS IS THE ORIGINAL WORKING OUTPUT-DIRECTORY LOGIC.
     *
     * Do not replace this with generic
     * getElementsByTagName("build") logic.
     */
    private static List<Path> parseOutputDirectories(
            Path effectivePom,
            Path moduleDirectory)
            throws IOException {

        try {

            DocumentBuilderFactory factory =
                    DocumentBuilderFactory.newInstance();

            factory.setNamespaceAware(true);

            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true);

            factory.setAttribute(
                    XMLConstants.ACCESS_EXTERNAL_DTD,
                    "");

            factory.setAttribute(
                    XMLConstants.ACCESS_EXTERNAL_SCHEMA,
                    "");

            Element project =
                    factory.newDocumentBuilder()
                            .parse(effectivePom.toFile())
                            .getDocumentElement();

            Element build =
                    child(
                            project,
                            "build");

            if (build == null) {
                return List.of();
            }

            Set<Path> outputs =
                    new LinkedHashSet<>();

            Element plugins =
                    child(
                            build,
                            "plugins");

            if (plugins != null) {

                for (Element plugin :
                        children(
                                plugins,
                                "plugin")) {

                    if (!"maven-compiler-plugin"
                            .equals(
                                    text(
                                            child(
                                                    plugin,
                                                    "artifactId")))) {

                        continue;
                    }

                    addCompilerOutput(
                            outputs,
                            child(
                                    plugin,
                                    "configuration"),
                            moduleDirectory);

                    Element executions =
                            child(
                                    plugin,
                                    "executions");

                    if (executions == null) {
                        continue;
                    }

                    for (Element execution :
                            children(
                                    executions,
                                    "execution")) {

                        Element goals =
                                child(
                                        execution,
                                        "goals");

                        boolean compiles =
                                goals != null
                                        && children(
                                                goals,
                                                "goal")
                                                .stream()
                                                .anyMatch(
                                                        goal ->
                                                                "compile"
                                                                        .equals(
                                                                                goal.getTextContent()
                                                                                        .trim()));

                        if (compiles) {

                            addCompilerOutput(
                                    outputs,
                                    child(
                                            execution,
                                            "configuration"),
                                    moduleDirectory);
                        }
                    }
                }
            }

            if (!outputs.isEmpty()) {
                return List.copyOf(
                        outputs);
            }

            String buildOutput =
                    text(
                            child(
                                    build,
                                    "outputDirectory"));

            if (buildOutput.isBlank()) {
                buildOutput =
                        "target/classes";
            }

            return List.of(
                    resolveOutputPath(
                            buildOutput,
                            moduleDirectory));

        } catch (IOException exception) {

            throw exception;

        } catch (Exception exception) {

            throw new IOException(
                    "Could not parse Maven effective POM "
                            + effectivePom,
                    exception);
        }
    }

    private static void addCompilerOutput(
            Set<Path> outputs,
            Element configuration,
            Path moduleDirectory)
            throws IOException {

        if (configuration == null) {
            return;
        }

        String output =
                text(
                        child(
                                configuration,
                                "outputDirectory"));

        if (!output.isBlank()) {

            outputs.add(
                    resolveOutputPath(
                            output,
                            moduleDirectory));
        }
    }

    private static Path resolveOutputPath(
            String outputDirectory,
            Path moduleDirectory)
            throws IOException {

        if (outputDirectory.contains("${")) {

            throw new IOException(
                    "Maven left an unresolved expression in compiler output "
                            + "directory: "
                            + outputDirectory);
        }

        Path output =
                Path.of(
                        outputDirectory);

        return (
                output.isAbsolute()
                        ? output
                        : moduleDirectory.resolve(
                                output)
        ).toAbsolutePath()
                .normalize();
    }

    private static Element child(
            Element parent,
            String name) {

        if (parent == null) {
            return null;
        }

        for (Node node =
                     parent.getFirstChild();
             node != null;
             node = node.getNextSibling()) {

            if (node instanceof Element element
                    && name.equals(
                            localName(element))) {

                return element;
            }
        }

        return null;
    }

    private static List<Element> children(
            Element parent,
            String name) {

        List<Element> result =
                new ArrayList<>();

        for (Node node =
                     parent.getFirstChild();
             node != null;
             node = node.getNextSibling()) {

            if (node instanceof Element element
                    && name.equals(
                            localName(element))) {

                result.add(element);
            }
        }

        return result;
    }

    private static String text(
            Element element) {

        return element == null
                ? ""
                : element.getTextContent()
                        .trim();
    }

    private static String localName(
            Element element) {

        return element.getLocalName() == null
                ? element.getTagName()
                : element.getLocalName();
    }

    private static String summarize(
            IOException exception) {

        String message =
                exception.getMessage();

        if (message == null
                || message.isBlank()) {

            return exception
                    .getClass()
                    .getSimpleName();
        }

        List<String> errors =
                message.lines()
                        .map(String::trim)
                        .filter(
                                line ->
                                        line.startsWith(
                                                "[ERROR]"))
                        .toList();

        String summary =
                errors.isEmpty()
                        ? message
                        : String.join(
                                "; ",
                                errors);

        return summary.length() <= 1200
                ? summary
                : summary.substring(
                                0,
                                1200)
                        + "...";
    }
}