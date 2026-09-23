package smartdeptest.dependency;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PomParser {
    public Map<String, Dependency> parse(String xml, String pomPath) throws Exception {
        Document document = parseXml(xml);
        Map<String, String> properties = readProperties(document);
        Map<String, Dependency> dependencies = new LinkedHashMap<>();
        Map<String, String> managedVersions = new LinkedHashMap<>();

        Element project = document.getDocumentElement();
        for (Element child : children(project, "dependencyManagement")) {
            for (Element dependenciesElement : children(child, "dependencies")) {
                for (Element dependency : children(dependenciesElement, "dependency")) {
                    Dependency parsed = readDependency(dependency, true, pomPath, properties);
                    if (parsed != null) {
                        dependencies.put(parsed.getKey(), parsed);
                        managedVersions.put(parsed.getDependencyKey(), parsed.getVersion());
                    }
                }
            }
        }
        for (Element dependenciesElement : children(project, "dependencies")) {
            for (Element dependencyElement : children(dependenciesElement, "dependency")) {
                Dependency parsed = readDependency(dependencyElement, false, pomPath, properties);
                if (parsed != null && "NOT_SPECIFIED".equals(parsed.getVersion())) {
                    String managedVersion = managedVersions.get(parsed.getDependencyKey());
                    if (managedVersion != null) {
                        parsed = withVersion(parsed, managedVersion);
                    }
                }
                if (parsed != null) dependencies.put(parsed.getKey(), parsed);
            }
        }
        return dependencies;
    }

    private Dependency readDependency(Element element, boolean managed, String pomPath,
                                      Map<String, String> properties) {
        String groupId = resolve(text(element, "groupId"), properties);
        String artifactId = resolve(text(element, "artifactId"), properties);
        if (groupId.isBlank() || artifactId.isBlank()) return null;
        String version = resolve(text(element, "version"), properties);
        if (version.isBlank()) version = "NOT_SPECIFIED";
        String scope = resolve(text(element, "scope"), properties);
        if (scope.isBlank()) scope = "compile";
        String type = resolve(text(element, "type"), properties);
        if (type.isBlank()) type = "jar";
        String classifier = resolve(text(element, "classifier"), properties);
        String optionalText = resolve(text(element, "optional"), properties);
        return new Dependency(groupId, artifactId, version, scope, type, classifier,
                Boolean.parseBoolean(optionalText), managed, pomPath);
    }

    private static Dependency withVersion(Dependency dependency, String version) {
        return new Dependency(dependency.getGroupId(), dependency.getArtifactId(), version,
                dependency.getScope(), dependency.getType(), dependency.getClassifier(),
                dependency.isOptional(), dependency.isDependencyManagement(), dependency.getPomPath());
    }

    private static Map<String, String> readProperties(Document document) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (Element propertiesElement : children(document.getDocumentElement(), "properties")) {
            NodeList nodes = propertiesElement.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    properties.put(node.getNodeName(), node.getTextContent().trim());
                }
            }
        }
        return properties;
    }

    private static String resolve(String value, Map<String, String> properties) {
        if (value == null) return "";
        String resolved = value.trim();
        for (int i = 0; i < 20; i++) {
            int start = resolved.indexOf("${");
            if (start < 0) break;
            int end = resolved.indexOf('}', start + 2);
            if (end < 0) break;
            String name = resolved.substring(start + 2, end);
            String replacement = properties.get(name);
            if (replacement == null) replacement = "${" + name + "}";
            String next = resolved.substring(0, start) + replacement + resolved.substring(end + 1);
            if (next.equals(resolved)) break;
            resolved = next;
        }
        return resolved;
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(Element parent, String name) {
        for (Element child : children(parent, name)) return child.getTextContent().trim();
        return "";
    }

    private static Iterable<Element> children(Element parent, String name) {
        java.util.List<Element> result = new java.util.ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && name.equals(node.getLocalName() == null ? node.getNodeName() : node.getLocalName())) {
                result.add((Element) node);
            }
        }
        return result;
    }
}
