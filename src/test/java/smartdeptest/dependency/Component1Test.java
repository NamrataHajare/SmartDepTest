package smartdeptest.dependency;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Component1Test {
    private final PomParser parser = new PomParser();
    private final DependencyComparator comparator = new DependencyComparator();

    @Test
    void noDependencyChanges() throws Exception {
        Map<String, Dependency> oldValues = parse(pom(dependency("a", "1", "compile")), "pom.xml");
        Map<String, Dependency> newValues = parse(pom(dependency("a", "1", "compile")), "pom.xml");
        assertEquals(0, changes(oldValues, newValues).size());
    }

    @Test
    void addedDependency() throws Exception {
        Map<String, Dependency> oldValues = parse(pom(""), "pom.xml");
        Map<String, Dependency> newValues = parse(pom(dependency("a", "1", "compile")), "pom.xml");
        assertEquals(DependencyChange.Type.ADDED, changes(oldValues, newValues).get(0).getChangeType());
    }

    @Test
    void removedDependency() throws Exception {
        Map<String, Dependency> oldValues = parse(pom(dependency("a", "1", "compile")), "pom.xml");
        Map<String, Dependency> newValues = parse(pom(""), "pom.xml");
        assertEquals(DependencyChange.Type.REMOVED, changes(oldValues, newValues).get(0).getChangeType());
    }

    @Test
    void versionUpdate() throws Exception {
        Map<String, Dependency> oldValues = parse(pom(dependency("a", "1", "compile")), "pom.xml");
        Map<String, Dependency> newValues = parse(pom(dependency("a", "2", "compile")), "pom.xml");
        DependencyChange change = changes(oldValues, newValues).get(0);
        assertEquals(DependencyChange.Type.UPDATED, change.getChangeType());
        assertEquals("1", change.getOldVersion());
        assertEquals("2", change.getNewVersion());
    }

    @Test
    void multipleDependencyUpdates() throws Exception {
        Map<String, Dependency> oldValues = parse(pom(dependency("a", "1", "compile"), dependency("b", "1", "compile")), "pom.xml");
        Map<String, Dependency> newValues = parse(pom(dependency("a", "2", "compile"), dependency("b", "2", "compile")), "pom.xml");
        assertEquals(2, changes(oldValues, newValues).size());
    }

    @Test
    void scopeChange() throws Exception {
        Map<String, Dependency> oldValues = parse(pom(dependency("a", "1", "test")), "pom.xml");
        Map<String, Dependency> newValues = parse(pom(dependency("a", "1", "compile")), "pom.xml");
        assertEquals(DependencyChange.Type.SCOPE_CHANGED, changes(oldValues, newValues).get(0).getChangeType());
    }

    @Test
    void propertyBasedVersionIsResolved() throws Exception {
        String oldPom = pomWithProperties("1.0", dependencyWithVersion("a", "${library.version}", "compile"));
        String newPom = pomWithProperties("2.0", dependencyWithVersion("a", "${library.version}", "compile"));
        DependencyChange change = changes(parse(oldPom, "pom.xml"), parse(newPom, "pom.xml")).get(0);
        assertEquals("1.0", change.getOldVersion());
        assertEquals("2.0", change.getNewVersion());
    }

    @Test
    void multiModulePomPathsRemainDistinct() throws Exception {
        Map<String, Dependency> root = parse(pom(dependency("a", "1", "compile")), "pom.xml");
        Map<String, Dependency> module = parse(pom(dependency("a", "2", "compile")), "module/pom.xml");
        assertEquals("pom.xml", root.values().iterator().next().getPomPath());
        assertEquals("module/pom.xml", module.values().iterator().next().getPomPath());
    }

    @Test
    void multipleChangedPomsCanBeCollected() throws Exception {
        Dependency oldDependency = parse(pom(dependency("a", "1", "compile")), "a/pom.xml").values().iterator().next();
        Dependency newDependency = parse(pom(dependency("a", "2", "compile")), "a/pom.xml").values().iterator().next();
        Dependency oldSecond = parse(pom(dependency("b", "1", "compile")), "b/pom.xml").values().iterator().next();
        Dependency newSecond = parse(pom(dependency("b", "2", "compile")), "b/pom.xml").values().iterator().next();
        Map<String, Dependency> oldValues = Map.of(oldDependency.getKey(), oldDependency, oldSecond.getKey(), oldSecond);
        Map<String, Dependency> newValues = Map.of(newDependency.getKey(), newDependency, newSecond.getKey(), newSecond);
        assertEquals(2, changes(oldValues, newValues).size());
    }

    @Test
    void invalidPomIsRejected() {
        assertThrows(Exception.class, () -> parser.parse("<project>", "pom.xml"));
    }

    @Test
    void dependencyManagementIsMarkedSeparately() throws Exception {
        String xml = "<project><dependencyManagement><dependencies>" + dependency("a", "1", "compile")
                + "</dependencies></dependencyManagement></project>";
        Dependency dependency = parser.parse(xml, "pom.xml").values().iterator().next();
        assertEquals(true, dependency.isDependencyManagement());
    }

    private Map<String, Dependency> parse(String xml, String path) throws Exception { return parser.parse(xml, path); }

    private java.util.List<DependencyChange> changes(Map<String, Dependency> oldValues, Map<String, Dependency> newValues) {
        return comparator.compare(oldValues, newValues, "pom.xml", "new", "old");
    }

    private String pom(String... dependencies) {
        return "<project><dependencies>" + String.join("", dependencies) + "</dependencies></project>";
    }

    private String pomWithProperties(String version, String dependency) {
        return "<project><properties><library.version>" + version + "</library.version></properties><dependencies>"
                + dependency + "</dependencies></project>";
    }

    private String dependency(String artifactId, String version, String scope) {
        return dependencyWithVersion(artifactId, version, scope);
    }

    private String dependencyWithVersion(String artifactId, String version, String scope) {
        return "<dependency><groupId>org.example</groupId><artifactId>" + artifactId + "</artifactId><version>"
                + version + "</version><scope>" + scope + "</scope></dependency>";
    }
}
