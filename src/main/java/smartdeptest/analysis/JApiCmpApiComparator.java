package smartdeptest.analysis;

import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.model.AccessModifier;
import japicmp.model.JApiChangeStatus;
import japicmp.model.JApiClass;
import japicmp.model.JApiConstructor;
import japicmp.model.JApiField;
import japicmp.model.JApiMethod;
import javassist.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

final class JApiCmpApiComparator {
    List<ApiChange> compare(Path oldJar, Path newJar,
                            List<Path> oldClasspath, List<Path> newClasspath,
                            boolean ignoreMissingClasses) throws IOException {
        validateJar(oldJar, "old");
        validateJar(newJar, "new");

        JarArchiveComparatorOptions options = new JarArchiveComparatorOptions();
        options.setAccessModifier(AccessModifier.PROTECTED);
        options.setOldClassPath(oldClasspath.stream().map(Path::toString).collect(Collectors.toList()));
        options.setNewClassPath(newClasspath.stream().map(Path::toString).collect(Collectors.toList()));
        options.getIgnoreMissingClasses().setIgnoreAllMissingClasses(ignoreMissingClasses);
        JarArchiveComparator comparator = new JarArchiveComparator(options);
        List<JApiClass> classes = comparator.compare(
                new JApiCmpArchive(oldJar.toFile(), "old"),
                new JApiCmpArchive(newJar.toFile(), "new"));

        List<ApiChange> changes = new ArrayList<>();
        for (JApiClass apiClass : classes) {
            addClassChange(changes, apiClass);
            for (JApiMethod method : apiClass.getMethods()) {
                if (method.getChangeStatus() != JApiChangeStatus.UNCHANGED) {
                    changes.add(methodChange(apiClass, method));
                }
            }
            for (JApiConstructor constructor : apiClass.getConstructors()) {
                if (constructor.getChangeStatus() != JApiChangeStatus.UNCHANGED) {
                    changes.add(constructorChange(apiClass, constructor));
                }
            }
            for (JApiField field : apiClass.getFields()) {
                if (field.getChangeStatus() != JApiChangeStatus.UNCHANGED) {
                    changes.add(fieldChange(apiClass, field));
                }
            }
        }
        pairChangedMethodSignatures(changes);
        return List.copyOf(changes);
    }

    private static void validateJar(Path jar, String label) throws IOException {
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IOException("The " + label + " dependency JAR is missing: " + jar);
        }
        try (JarFile ignored = new JarFile(jar.toFile())) {
            // Opening the archive validates that it is a readable JAR.
        } catch (IOException exception) {
            throw new IOException("The " + label + " dependency JAR is invalid: " + jar, exception);
        }
    }

    private static void addClassChange(List<ApiChange> changes, JApiClass apiClass) {
        if (apiClass.getChangeStatus() == JApiChangeStatus.UNCHANGED) return;
        if (apiClass.getChangeStatus() == JApiChangeStatus.MODIFIED && !hasClassShapeChange(apiClass)) return;
        String owner = apiClass.getFullyQualifiedName().replace('.', '/');
        changes.add(new ApiChange(classKind(apiClass.getChangeStatus()), owner, "",
                apiClass.getOldClass().map(Object::toString).orElse(""),
                apiClass.getNewClass().map(Object::toString).orElse(""), "", "",
                !apiClass.isBinaryCompatible()));
    }

    private static boolean hasClassShapeChange(JApiClass apiClass) {
        return apiClass.getModifiers().stream()
                .anyMatch(modifier -> modifier.getChangeStatus() != JApiChangeStatus.UNCHANGED)
                || apiClass.getSuperclass().getChangeStatus() != JApiChangeStatus.UNCHANGED
                || apiClass.getInterfaces().stream()
                .anyMatch(contract -> contract.getChangeStatus() != JApiChangeStatus.UNCHANGED);
    }

    private static ApiChange methodChange(JApiClass apiClass, JApiMethod method) {
        String owner = apiClass.getFullyQualifiedName().replace('.', '/');
        String oldDescriptor = method.getOldMethod()
                .map(value -> value.getMethodInfo2().getDescriptor()).orElse("");
        String newDescriptor = method.getNewMethod()
                .map(value -> value.getMethodInfo2().getDescriptor()).orElse("");
        return new ApiChange(methodKind(method.getChangeStatus()), owner, method.getName(),
                method.getOldMethod().map(Object::toString).orElse(""),
                method.getNewMethod().map(Object::toString).orElse(""),
                oldDescriptor, newDescriptor, potentiallyIncompatible(apiClass, method));
    }

    private static boolean potentiallyIncompatible(JApiClass apiClass, JApiMethod method) {
        return !method.isBinaryCompatible() || method.getNewMethod()
                .map(newMethod -> Modifier.isAbstract(newMethod.getModifiers())).orElse(false);
    }

    private static ApiChange constructorChange(JApiClass apiClass, JApiConstructor constructor) {
        String owner = apiClass.getFullyQualifiedName().replace('.', '/');
        String oldDescriptor = constructor.getOldConstructor()
                .map(value -> value.getMethodInfo2().getDescriptor()).orElse("");
        String newDescriptor = constructor.getNewConstructor()
                .map(value -> value.getMethodInfo2().getDescriptor()).orElse("");
        return new ApiChange(methodKind(constructor.getChangeStatus()), owner, "<init>",
                constructor.getOldConstructor().map(Object::toString).orElse(""),
                constructor.getNewConstructor().map(Object::toString).orElse(""),
                oldDescriptor, newDescriptor, !constructor.isBinaryCompatible());
    }

    private static ApiChange fieldChange(JApiClass apiClass, JApiField field) {
        String owner = apiClass.getFullyQualifiedName().replace('.', '/');
        String oldDescriptor = field.getOldFieldOptional()
                .map(value -> value.getFieldInfo2().getDescriptor()).orElse("");
        String newDescriptor = field.getNewFieldOptional()
                .map(value -> value.getFieldInfo2().getDescriptor()).orElse("");
        return new ApiChange(fieldKind(field.getChangeStatus()), owner, field.getName(),
                field.getOldFieldOptional().map(Object::toString).orElse(""),
                field.getNewFieldOptional().map(Object::toString).orElse(""),
                oldDescriptor, newDescriptor, !field.isBinaryCompatible());
    }

    private static ApiChange.Kind classKind(JApiChangeStatus status) {
        return switch (status) {
            case NEW -> ApiChange.Kind.CLASS_ADDED;
            case REMOVED -> ApiChange.Kind.CLASS_REMOVED;
            case MODIFIED -> ApiChange.Kind.CLASS_MODIFIED;
            case UNCHANGED -> throw new IllegalArgumentException("Unchanged class has no API change.");
        };
    }

    private static ApiChange.Kind methodKind(JApiChangeStatus status) {
        return switch (status) {
            case NEW -> ApiChange.Kind.METHOD_ADDED;
            case REMOVED -> ApiChange.Kind.METHOD_REMOVED;
            case MODIFIED -> ApiChange.Kind.METHOD_MODIFIED;
            case UNCHANGED -> throw new IllegalArgumentException("Unchanged method has no API change.");
        };
    }

    private static ApiChange.Kind fieldKind(JApiChangeStatus status) {
        return switch (status) {
            case NEW -> ApiChange.Kind.FIELD_ADDED;
            case REMOVED -> ApiChange.Kind.FIELD_REMOVED;
            case MODIFIED -> ApiChange.Kind.FIELD_MODIFIED;
            case UNCHANGED -> throw new IllegalArgumentException("Unchanged field has no API change.");
        };
    }

    private static void pairChangedMethodSignatures(List<ApiChange> changes) {
        Map<String, List<ApiChange>> removed = new java.util.LinkedHashMap<>();
        Map<String, List<ApiChange>> added = new java.util.LinkedHashMap<>();
        for (ApiChange change : changes) {
            if (change.kind() == ApiChange.Kind.METHOD_REMOVED) {
                removed.computeIfAbsent(methodGroup(change), ignored -> new ArrayList<>()).add(change);
            } else if (change.kind() == ApiChange.Kind.METHOD_ADDED) {
                added.computeIfAbsent(methodGroup(change), ignored -> new ArrayList<>()).add(change);
            }
        }
        for (Map.Entry<String, List<ApiChange>> entry : removed.entrySet()) {
            List<ApiChange> oldChanges = entry.getValue();
            List<ApiChange> newChanges = added.get(entry.getKey());
            if (oldChanges.size() != 1 || newChanges == null || newChanges.size() != 1) continue;
            ApiChange oldChange = oldChanges.get(0);
            ApiChange newChange = newChanges.get(0);
            changes.remove(oldChange);
            changes.remove(newChange);
            changes.add(new ApiChange(ApiChange.Kind.METHOD_MODIFIED, oldChange.className(),
                    oldChange.memberName(), oldChange.oldSignature(), newChange.newSignature(),
                    oldChange.oldDescriptor(), newChange.newDescriptor(),
                    oldChange.potentiallyIncompatible() || newChange.potentiallyIncompatible()));
        }
    }

    private static String methodGroup(ApiChange change) {
        return change.className() + "#" + change.memberName();
    }
}