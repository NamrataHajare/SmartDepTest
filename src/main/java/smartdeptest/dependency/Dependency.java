package smartdeptest.dependency;

import java.util.Objects;

public final class Dependency {
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String scope;
    private final String type;
    private final String classifier;
    private final boolean optional;
    private final boolean dependencyManagement;
    private final String pomPath;

    public Dependency(String groupId, String artifactId, String version, String scope,
                      String type, String classifier, boolean optional,
                      boolean dependencyManagement, String pomPath) {
        this.groupId = value(groupId);
        this.artifactId = value(artifactId);
        this.version = value(version);
        this.scope = value(scope);
        this.type = value(type);
        this.classifier = value(classifier);
        this.optional = optional;
        this.dependencyManagement = dependencyManagement;
        this.pomPath = value(pomPath);
    }

    private static String value(String text) { return text == null ? "" : text; }
    public String getGroupId() { return groupId; }
    public String getArtifactId() { return artifactId; }
    public String getVersion() { return version; }
    public String getScope() { return scope; }
    public String getType() { return type; }
    public String getClassifier() { return classifier; }
    public boolean isOptional() { return optional; }
    public boolean isDependencyManagement() { return dependencyManagement; }
    public String getPomPath() { return pomPath; }
    public String getDependencyKey() { return groupId + ":" + artifactId; }
    public String getKey() { return (dependencyManagement ? "managed:" : "direct:") + getDependencyKey(); }

    public boolean sameMetadata(Dependency other) {
        return Objects.equals(version, other.version)
                && Objects.equals(scope, other.scope)
                && Objects.equals(type, other.type)
                && Objects.equals(classifier, other.classifier)
                && optional == other.optional;
    }
}
