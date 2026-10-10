
package smartdeptest.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class MavenArtifactResolver {

    @FunctionalInterface
    interface MavenInvoker {
        String run(Path workingDirectory, List<String> arguments) throws IOException;
    }

    record ResolvedArtifact(Path jar, String source) {
    }

    private static final Pattern TRANSFER_SOURCE = Pattern.compile(
            "(?m)^.*(?:Downloaded|Downloading) from ([^\\s:]+): (\\S+).*$");

    private static final List<String> CACHED_MISS_MARKERS = List.of(
            "failure was cached",
            "cached in the local repository",
            "previous attempt",
            "will not be reattempted",
            "not reattempted until");

    private final MavenInvoker mavenInvoker;
    private final Map<String, ResolvedArtifact> resolved = new HashMap<>();
    private final Map<String, List<Path>> apiClasspaths = new HashMap<>();

    MavenArtifactResolver() {
        this(MavenCommandRunner::run);
    }

    MavenArtifactResolver(MavenInvoker mavenInvoker) {
        this.mavenInvoker = mavenInvoker;
    }

    ResolvedArtifact resolveJar(
            Path projectDirectory,
            String pomPath,
            String groupId,
            String artifactId,
            String version,
            String classifier) throws IOException {

        String effectiveClassifier = classifier == null ? "" : classifier;
        String coordinate = groupId + ":" + artifactId + ":" + version + ":jar"
                + (effectiveClassifier.isBlank() ? "" : ":" + effectiveClassifier);

        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Path projectPom = projectRoot.resolve(pomPath).normalize();

        if (!projectPom.startsWith(projectRoot) || !Files.isRegularFile(projectPom)) {
            throw new IOException("Target Maven POM does not exist: " + projectPom);
        }

        String cacheKey = projectPom + "|" + coordinate;
        ResolvedArtifact cached = resolved.get(cacheKey);

        if (cached != null && Files.isRegularFile(cached.jar())) {
            return cached;
        }

        if (!validCoordinatePart(groupId)
                || !validCoordinatePart(artifactId)
                || !validCoordinatePart(version)
                || (!effectiveClassifier.isBlank()
                        && !validCoordinatePart(effectiveClassifier))) {
            throw new IOException(
                    "Dependency coordinates are incomplete or contain an unresolved Maven property: "
                            + coordinate);
        }

        Path destinationDirectory = Files.createTempDirectory("smartdeptest-resolved-artifact-");
        destinationDirectory.toFile().deleteOnExit();

        Path jar = destinationDirectory.resolve(
                artifactId + "-" + version
                        + (effectiveClassifier.isBlank() ? "" : "-" + effectiveClassifier)
                        + ".jar");
        jar.toFile().deleteOnExit();

        try {
            String artifactCoordinate = groupId + ":" + artifactId + ":" + version + ":jar"
                    + (effectiveClassifier.isBlank() ? "" : ":" + effectiveClassifier);

            String output = runWithCachedMissRetry(
                    mavenInvoker,
                    projectRoot,
                    List.of(
                            "mvn",
                            "-f",
                            projectPom.toString(),
                            "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:copy",
                            "-Dartifact=" + artifactCoordinate,
                            "-DoutputDirectory=" + destinationDirectory));

            if (!Files.isRegularFile(jar)) {
                throw new IOException(
                        "Maven completed without producing the requested JAR at " + jar
                                + ". Maven output: " + output);
            }

            String source = repositorySource(output);
            ResolvedArtifact artifact = new ResolvedArtifact(jar, source);
            resolved.put(cacheKey, artifact);
            return artifact;

        } catch (IOException exception) {
            throw new IOException(
                    "Could not resolve " + coordinate
                            + " using target project's Maven POM "
                            + projectPom + ". Maven error: " + exception.getMessage(),
                    exception);
        }
    }

    List<Path> resolveApiClasspath(
            Path projectDirectory,
            String pomPath,
            String groupId,
            String artifactId,
            String version,
            String classifier,
            ResolvedArtifact dependencyArtifact) throws IOException {

        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Path projectPom = projectRoot.resolve(pomPath).normalize();

        String effectiveClassifier = classifier == null ? "" : classifier;
        String coordinate = groupId + ":" + artifactId + ":" + version + ":jar"
                + (effectiveClassifier.isBlank() ? "" : ":" + effectiveClassifier);

        String cacheKey = projectPom + "|" + coordinate;
        List<Path> baseClasspath = apiClasspaths.get(cacheKey);

        if (baseClasspath == null) {
            ClasspathPom classpathPom = null;
            Path output = Files.createTempFile(
                    "smartdeptest-api-classpath-", ".txt");

            try {
                classpathPom = createClasspathPom(
                        projectPom,
                        groupId,
                        artifactId,
                        version,
                        effectiveClassifier);

                List<String> entries = new ArrayList<>();
                entries.add("mvn");
                entries.add("-f");
                entries.add(classpathPom.wrapper().toString());
                entries.add(
                        "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:build-classpath");
                entries.add("-DincludeScope=compile");
                entries.add("-Dmdep.outputFile=" + output);

                runWithCachedMissRetry(mavenInvoker, projectRoot, entries);

                List<Path> classpath = new ArrayList<>();

                if (Files.isRegularFile(output)) {
                    String value = Files.readString(output).trim();

                    if (!value.isBlank()) {
                        for (String entry : value.split(
                                Pattern.quote(java.io.File.pathSeparator))) {
                            Path path = Path.of(entry);

                            if (Files.exists(path)) {
                                classpath.add(path.toAbsolutePath().normalize());
                            }
                        }
                    }
                }

                baseClasspath = classpath.stream().distinct().toList();
                apiClasspaths.put(cacheKey, baseClasspath);

            } catch (IOException exception) {
                throw new IOException(
                        "Could not resolve the API classpath for "
                                + groupId + ":" + artifactId + ":" + version
                                + " using target project's Maven POM "
                                + projectPom + ". Maven error: "
                                + exception.getMessage(),
                        exception);

            } finally {
                if (classpathPom != null) {
                    Files.deleteIfExists(classpathPom.wrapper());
                    Files.deleteIfExists(classpathPom.parent());
                }

                Files.deleteIfExists(output);
            }
        }

        List<Path> classpath = new ArrayList<>(baseClasspath);
        classpath.add(dependencyArtifact.jar().toAbsolutePath().normalize());

        return classpath.stream().distinct().toList();
    }

    private static ClasspathPom createClasspathPom(
            Path projectPom,
            String groupId,
            String artifactId,
            String version,
            String classifier) throws IOException {

        Path parentPom = null;

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            Element project = factory.newDocumentBuilder()
                    .parse(projectPom.toFile())
                    .getDocumentElement();

            normalizeLegacyRepositories(project);

            String parentGroupId = childText(project, "groupId");
            String parentArtifactId = childText(project, "artifactId");
            String parentVersion = childText(project, "version");

            Element parent = child(project, "parent");

            if (parentGroupId.isBlank() && parent != null) {
                parentGroupId = childText(parent, "groupId");
            }

            if (parentVersion.isBlank() && parent != null) {
                parentVersion = childText(parent, "version");
            }

            Map<String, String> properties = new HashMap<>();
            Element propertyElement = child(project, "properties");

            if (propertyElement != null) {
                for (Node property = propertyElement.getFirstChild(); property != null; property = property
                        .getNextSibling()) {

                    if (property instanceof Element element) {
                        properties.put(
                                localName(element),
                                element.getTextContent().trim());
                    }
                }
            }

            parentGroupId = resolveProperties(parentGroupId, properties);
            parentArtifactId = resolveProperties(parentArtifactId, properties);
            parentVersion = resolveProperties(parentVersion, properties);

            if (parentGroupId.isBlank()
                    || parentArtifactId.isBlank()
                    || parentVersion.isBlank()
                    || parentGroupId.contains("${")
                    || parentArtifactId.contains("${")
                    || parentVersion.contains("${")) {
                throw new IOException(
                        "Could not determine the target POM's Maven coordinates.");
            }

            Element packaging = child(project, "packaging");

            if (packaging == null) {
                packaging = project.getOwnerDocument().createElementNS(
                        project.getNamespaceURI(), "packaging");

                Element versionElement = child(project, "version");
                Element artifactIdElement = child(project, "artifactId");

                Node anchor = versionElement == null
                        ? artifactIdElement
                        : versionElement;

                if (anchor == null) {
                    throw new IOException(
                            "Could not locate Maven coordinates in the target POM.");
                }

                project.insertBefore(packaging, anchor.getNextSibling());
            }

            packaging.setTextContent("pom");

            parentPom = Files.createTempFile(
                    projectPom.getParent(),
                    ".smartdeptest-parent-",
                    ".pom");

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(
                    XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(
                    XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");

            transformerFactory.newTransformer().transform(
                    new DOMSource(project.getOwnerDocument()),
                    new StreamResult(parentPom.toFile()));

            String wrapper = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\""
                    + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                    + " xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0"
                    + " https://maven.apache.org/xsd/maven-4.0.0.xsd\">"
                    + "<modelVersion>4.0.0</modelVersion><parent><groupId>"
                    + xml(parentGroupId)
                    + "</groupId><artifactId>"
                    + xml(parentArtifactId)
                    + "</artifactId><version>"
                    + xml(parentVersion)
                    + "</version><relativePath>"
                    + xml(parentPom.getFileName().toString())
                    + "</relativePath></parent><artifactId>smartdeptest-classpath-"
                    + java.util.UUID.randomUUID()
                    + "</artifactId><dependencies><dependency><groupId>"
                    + xml(groupId)
                    + "</groupId><artifactId>"
                    + xml(artifactId)
                    + "</artifactId><version>"
                    + xml(version)
                    + "</version><type>jar</type>"
                    + (classifier.isBlank()
                            ? ""
                            : "<classifier>" + xml(classifier) + "</classifier>")
                    + "<scope>compile</scope></dependency></dependencies></project>";

            Path wrapperPom = Files.createTempFile(
                    projectPom.getParent(),
                    ".smartdeptest-classpath-",
                    ".pom");

            Files.writeString(wrapperPom, wrapper);

            return new ClasspathPom(wrapperPom, parentPom);

        } catch (IOException exception) {
            if (parentPom != null) {
                Files.deleteIfExists(parentPom);
            }

            throw exception;

        } catch (Exception exception) {
            if (parentPom != null) {
                Files.deleteIfExists(parentPom);
            }

            throw new IOException(
                    "Could not create a temporary API classpath POM for "
                            + projectPom + ".",
                    exception);
        }
    }

    private static void normalizeLegacyRepositories(Element project) {
        Element repositories = child(project, "repositories");

        if (repositories == null) {
            return;
        }

        for (Node node = repositories.getFirstChild(); node != null; node = node.getNextSibling()) {

            if (!(node instanceof Element repository)
                    || !localName(repository).equals("repository")) {
                continue;
            }

            Element urlElement = child(repository, "url");
            Element idElement = child(repository, "id");

            if (urlElement == null || idElement == null) {
                continue;
            }

            String url = urlElement.getTextContent().trim();

            if (url.equalsIgnoreCase(
                    "http://download.osgeo.org/webdav/geotools")
                    || url.equalsIgnoreCase(
                            "http://download.osgeo.org/webdav/geotools/")) {

                urlElement.setTextContent(
                        "https://repo.osgeo.org/repository/release/");
                idElement.setTextContent("osgeo-releases");

            } else if (url.equalsIgnoreCase(
                    "http://maven.geo-solutions.it")
                    || url.equalsIgnoreCase(
                            "http://maven.geo-solutions.it/")) {

                urlElement.setTextContent(
                        "https://maven.geo-solutions.it/");
            }
        }
    }

    private record ClasspathPom(Path wrapper, Path parent) {
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {

            if (node instanceof Element element
                    && localName(element).equals(name)) {
                return element;
            }
        }

        return null;
    }

    private static String childText(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().trim();
    }

    private static String localName(Element element) {
        return element.getLocalName() == null
                ? element.getTagName()
                : element.getLocalName();
    }

    private static String resolveProperties(
            String value,
            Map<String, String> properties) {

        String resolved = value;

        for (int attempt = 0; attempt < properties.size(); attempt++) {
            Matcher matcher = Pattern.compile("\\$\\{([^}]+)}").matcher(resolved);

            StringBuffer result = new StringBuffer();
            boolean replaced = false;

            while (matcher.find()) {
                String property = properties.get(matcher.group(1));

                if (property != null) {
                    matcher.appendReplacement(
                            result,
                            Matcher.quoteReplacement(property));
                    replaced = true;
                }
            }

            matcher.appendTail(result);
            resolved = result.toString();

            if (!replaced) {
                break;
            }
        }

        return resolved;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    static String runWithCachedMissRetry(
            MavenInvoker mavenInvoker,
            Path workingDirectory,
            List<String> arguments) throws IOException {

        try {
            return mavenInvoker.run(workingDirectory, arguments);

        } catch (IOException firstFailure) {
            String message = firstFailure.getMessage();

            if (message == null
                    || CACHED_MISS_MARKERS.stream().noneMatch(
                            marker -> message.toLowerCase(
                                    java.util.Locale.ROOT).contains(marker))) {
                throw firstFailure;
            }

            List<String> retryArguments = new ArrayList<>(arguments);
            int mavenIndex = retryArguments.indexOf("mvn");

            retryArguments.add(
                    mavenIndex >= 0 ? mavenIndex + 1 : 0,
                    "-U");

            try {
                return mavenInvoker.run(workingDirectory, retryArguments);

            } catch (IOException retryFailure) {
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        }
    }

    private static String repositorySource(String mavenOutput) {
        Matcher matcher = TRANSFER_SOURCE.matcher(mavenOutput);
        String lastSource = null;

        while (matcher.find()) {
            lastSource = matcher.group(1) + " (" + matcher.group(2) + ")";
        }

        return lastSource == null
                ? "Maven local cache or target project's configured repositories/settings (Maven-selected)"
                : lastSource;
    }

    private static boolean validCoordinatePart(String value) {
        return value != null
                && value.matches("[A-Za-z0-9_.+-]+")
                && !value.contains("${")
                && !value.isBlank();
    }

    static Path artifactPathSuffix(
            String groupId,
            String artifactId,
            String version,
            String classifier) {

        String fileName = artifactId + "-" + version
                + (classifier == null || classifier.isBlank()
                        ? ""
                        : "-" + classifier)
                + ".jar";

        Path groupPath = Path.of(
                groupId.replace('.', java.io.File.separatorChar));

        return groupPath.resolve(artifactId)
                .resolve(version)
                .resolve(fileName);
    }
}
