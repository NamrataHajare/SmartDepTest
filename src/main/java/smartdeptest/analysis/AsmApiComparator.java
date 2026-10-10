package smartdeptest.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

final class AsmApiComparator {
    private static final int CLASS_FLAGS = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT
            | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM
            | Opcodes.ACC_RECORD | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
    private static final int METHOD_FLAGS = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE
            | Opcodes.ACC_STRICT | Opcodes.ACC_BRIDGE | Opcodes.ACC_VARARGS
            | Opcodes.ACC_SYNTHETIC;
    private static final int FIELD_FLAGS = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_VOLATILE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_ENUM
            | Opcodes.ACC_SYNTHETIC;
    List<ApiChange> compare(Path oldJar, Path newJar) throws IOException {
        Map<String, ClassApi> oldClasses = readClasses(oldJar, "old");
        Map<String, ClassApi> newClasses = readClasses(newJar, "new");
        Set<String> classNames = new TreeSet<>(oldClasses.keySet());
        classNames.addAll(newClasses.keySet());

        List<ApiChange> changes = new ArrayList<>();
        for (String className : classNames) {
            ClassApi oldClass = oldClasses.get(className);
            ClassApi newClass = newClasses.get(className);
            if (!isApiVisible(oldClass) && !isApiVisible(newClass)) {
                continue;
            }

            addClassChange(changes, oldClass, newClass);
            compareMembers(changes, oldClass, newClass, true);
            compareMembers(changes, oldClass, newClass, false);
        }

        changes.sort(Comparator.comparing(ApiChange::className)
                .thenComparing(change -> change.kind().ordinal())
                .thenComparing(ApiChange::memberName)
                .thenComparing(ApiChange::oldDescriptor)
                .thenComparing(ApiChange::newDescriptor));
        return List.copyOf(changes);
    }

    private static Map<String, ClassApi> readClasses(Path jar, String label) throws IOException {
        validateJar(jar, label);
        Map<String, ClassApi> classes = new LinkedHashMap<>();

        try (JarFile archive = new JarFile(
                jar.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
            List<JarEntry> entries = archive.versionedStream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class"))
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .toList();
            for (JarEntry entry : entries) {
                ClassApi api;
                try (InputStream input = archive.getInputStream(entry)) {
                    api = readClass(input);
                } catch (RuntimeException exception) {
                    throw new IOException("Could not read class file " + entry.getName()
                            + " from " + jar, exception);
                }

                // A JAR can contain multiple entries defining the same binary class.
                // Use the first entry in stable archive-name order rather than emitting
                // duplicate API changes or making the result depend on hash iteration.
                classes.putIfAbsent(api.name(), api);
            }
        } catch (IOException exception) {
            throw new IOException("Could not read the " + label
                    + " dependency JAR: " + jar, exception);
        }
        return classes;
    }

    private static ClassApi readClass(InputStream input) throws IOException {
        ClassData data = new ClassData();
        ClassReader reader = new ClassReader(input);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                data.name = name;
                data.access = access;
                data.signature = signature;
                data.superName = superName;
                data.interfaces = interfaces == null ? List.of() : List.of(interfaces.clone());
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                data.fields.put(new MemberKey(name, descriptor),
                        new MemberApi(name, descriptor, access, signature, List.of(),
                                value == null ? null : String.valueOf(value)));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"<clinit>".equals(name)) {
                    List<String> declaredExceptions = exceptions == null
                            ? List.of()
                            : List.of(exceptions.clone());
                    data.methods.put(new MemberKey(name, descriptor),
                            new MemberApi(name, descriptor, access, signature,
                                    declaredExceptions, ""));
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (data.name == null) {
            throw new IOException("Class file did not contain a class header.");
        }
        return new ClassApi(data.name, data.access, data.signature, data.superName,
                data.interfaces, data.methods, data.fields);
    }

    private static void validateJar(Path jar, String label) throws IOException {
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IOException("The " + label + " dependency JAR is missing: " + jar);
        }
        try (JarFile ignored = new JarFile(jar.toFile())) {
            // Opening the archive validates that it is a readable JAR.
        } catch (IOException exception) {
            throw new IOException("The " + label + " dependency JAR is invalid: " + jar,
                    exception);
        }
    }

    private static void addClassChange(
            List<ApiChange> changes, ClassApi oldClass, ClassApi newClass) {
        if (oldClass == null || newClass == null) {
            ClassApi present = oldClass == null ? newClass : oldClass;
            ApiChange.Kind kind = oldClass == null
                    ? ApiChange.Kind.CLASS_ADDED : ApiChange.Kind.CLASS_REMOVED;
            changes.add(new ApiChange(kind, present.name(), "",
                    oldClass == null ? "" : classSignature(oldClass),
                    newClass == null ? "" : classSignature(newClass),
                    "", "", oldClass != null));
            return;
        }

        if (((oldClass.access() ^ newClass.access()) & CLASS_FLAGS) != 0
                || !equal(oldClass.superName(), newClass.superName())
                || !oldClass.interfaces().equals(newClass.interfaces())
                || !equal(oldClass.signature(), newClass.signature())) {
            boolean incompatible = visibilityReduced(oldClass.access(), newClass.access())
                    || !equal(oldClass.superName(), newClass.superName())
                    || removesInterface(oldClass.interfaces(), newClass.interfaces())
                    || addsIncompatibleClassFlag(oldClass.access(), newClass.access());
            changes.add(new ApiChange(ApiChange.Kind.CLASS_MODIFIED, oldClass.name(), "",
                    classSignature(oldClass), classSignature(newClass), "", "", incompatible));
        }
    }

    private static boolean removesInterface(List<String> oldInterfaces, List<String> newInterfaces) {
        return oldInterfaces.stream().anyMatch(contract -> !newInterfaces.contains(contract));
    }

    private static boolean addsIncompatibleClassFlag(int oldAccess, int newAccess) {
        int added = newAccess & ~oldAccess;
        return (added & (Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT)) != 0
                || ((oldAccess ^ newAccess) & Opcodes.ACC_INTERFACE) != 0;
    }

    private static void compareMembers(
            List<ApiChange> changes, ClassApi oldClass, ClassApi newClass, boolean methods) {
        Map<MemberKey, MemberApi> oldMembers = oldClass == null ? Map.of()
                : methods ? oldClass.methods() : oldClass.fields();
        Map<MemberKey, MemberApi> newMembers = newClass == null ? Map.of()
                : methods ? newClass.methods() : newClass.fields();
        Set<MemberKey> keys = new TreeSet<>(Comparator.comparing(MemberKey::name)
                .thenComparing(MemberKey::descriptor));
        keys.addAll(oldMembers.keySet());
        keys.addAll(newMembers.keySet());

        List<MemberApi> removed = new ArrayList<>();
        List<MemberApi> added = new ArrayList<>();
        for (MemberKey key : keys) {
            MemberApi oldMember = oldMembers.get(key);
            MemberApi newMember = newMembers.get(key);
            boolean oldVisible = isApiVisible(oldClass, oldMember);
            boolean newVisible = isApiVisible(newClass, newMember);
            if (oldMember != null && newMember != null) {
                if (oldVisible && !newVisible && oldMember.equals(newMember)) {
                    removed.add(oldMember);
                } else if (!oldVisible && newVisible && oldMember.equals(newMember)) {
                    added.add(newMember);
                } else if ((oldVisible || newVisible) && !oldMember.equals(newMember)) {
                    changes.add(memberChange(methods, ApiChange.Kind.METHOD_MODIFIED,
                            ApiChange.Kind.FIELD_MODIFIED, oldClass, newClass,
                            oldMember, newMember, incompatibleMemberChange(methods,
                                    oldMember, newMember)));
                }
            } else if (oldMember != null && oldVisible) {
                removed.add(oldMember);
            } else if (newMember != null && newVisible) {
                added.add(newMember);
            }
        }

        if (methods) {
            pairChangedMethodDescriptors(changes, oldClass, newClass, removed, added);
        } else {
            pairChangedFieldDescriptors(changes, oldClass, newClass, removed, added);
        }

        for (MemberApi member : removed) {
            changes.add(memberChange(methods, ApiChange.Kind.METHOD_REMOVED,
                    ApiChange.Kind.FIELD_REMOVED, oldClass, null, member, null, true));
        }
        for (MemberApi member : added) {
            boolean incompatible = methods && (member.access() & Opcodes.ACC_ABSTRACT) != 0;
            changes.add(memberChange(methods, ApiChange.Kind.METHOD_ADDED,
                    ApiChange.Kind.FIELD_ADDED, null, newClass, null, member, incompatible));
        }
    }

    private static void pairChangedMethodDescriptors(
            List<ApiChange> changes, ClassApi oldClass, ClassApi newClass,
            List<MemberApi> removed, List<MemberApi> added) {
        pairUniqueByName(removed, added, (oldMember, newMember) ->
                changes.add(memberChange(true, ApiChange.Kind.METHOD_MODIFIED,
                        ApiChange.Kind.FIELD_MODIFIED, oldClass, newClass,
                        oldMember, newMember, true)));
    }

    private static void pairChangedFieldDescriptors(
            List<ApiChange> changes, ClassApi oldClass, ClassApi newClass,
            List<MemberApi> removed, List<MemberApi> added) {
        pairUniqueByName(removed, added, (oldMember, newMember) ->
                changes.add(memberChange(false, ApiChange.Kind.METHOD_MODIFIED,
                        ApiChange.Kind.FIELD_MODIFIED, oldClass, newClass,
                        oldMember, newMember, true)));
    }

    private static void pairUniqueByName(
            List<MemberApi> removed, List<MemberApi> added,
            java.util.function.BiConsumer<MemberApi, MemberApi> pair) {
        Map<String, List<MemberApi>> oldByName = groupByName(removed);
        Map<String, List<MemberApi>> newByName = groupByName(added);
        for (Map.Entry<String, List<MemberApi>> entry : oldByName.entrySet()) {
            List<MemberApi> oldGroup = entry.getValue();
            List<MemberApi> newGroup = newByName.get(entry.getKey());
            if (oldGroup.size() == 1 && newGroup != null && newGroup.size() == 1) {
                MemberApi oldMember = oldGroup.get(0);
                MemberApi newMember = newGroup.get(0);
                pair.accept(oldMember, newMember);
                removed.remove(oldMember);
                added.remove(newMember);
            }
        }
    }

    private static Map<String, List<MemberApi>> groupByName(List<MemberApi> members) {
        Map<String, List<MemberApi>> groups = new HashMap<>();
        for (MemberApi member : members) {
            groups.computeIfAbsent(member.name(), ignored -> new ArrayList<>()).add(member);
        }
        return groups;
    }

    private static ApiChange memberChange(
            boolean method,
            ApiChange.Kind methodKind,
            ApiChange.Kind fieldKind,
            ClassApi oldClass,
            ClassApi newClass,
            MemberApi oldMember,
            MemberApi newMember,
            boolean incompatible) {
        String owner = oldClass == null ? newClass.name() : oldClass.name();
        MemberApi present = oldMember == null ? newMember : oldMember;
        String memberName = present.name();
        return new ApiChange(method ? methodKind : fieldKind, owner, memberName,
                oldMember == null ? "" : memberSignature(oldMember, method),
                newMember == null ? "" : memberSignature(newMember, method),
                oldMember == null ? "" : oldMember.descriptor(),
                newMember == null ? "" : newMember.descriptor(),
                incompatible);
    }

    private static boolean incompatibleMemberChange(
            boolean method, MemberApi oldMember, MemberApi newMember) {
        if (visibilityReduced(oldMember.access(), newMember.access())
                || !oldMember.descriptor().equals(newMember.descriptor())) {
            return true;
        }
        int addedFlags = newMember.access() & ~oldMember.access();
        if (method) {
            return (addedFlags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_FINAL)) != 0
                    || ((oldMember.access() ^ newMember.access()) & Opcodes.ACC_STATIC) != 0;
        }
        return (addedFlags & Opcodes.ACC_FINAL) != 0
                || ((oldMember.access() ^ newMember.access()) & Opcodes.ACC_STATIC) != 0;
    }

    private static boolean visibilityReduced(int oldAccess, int newAccess) {
        return visibility(newAccess) < visibility(oldAccess);
    }

    private static int visibility(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) return 3;
        if ((access & Opcodes.ACC_PROTECTED) != 0) return 2;
        if ((access & Opcodes.ACC_PRIVATE) != 0) return 0;
        return 1;
    }

    private static boolean isApiVisible(ClassApi apiClass) {
        return apiClass != null && (apiClass.access()
                & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
    }

    private static boolean isApiVisible(ClassApi apiClass, MemberApi member) {
        return isApiVisible(apiClass) && member != null
                && (member.access() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
    }

    private static String classSignature(ClassApi apiClass) {
        StringBuilder signature = new StringBuilder();
        appendModifiers(signature, apiClass.access(), CLASS_FLAGS & ~Opcodes.ACC_ENUM);
        if ((apiClass.access() & Opcodes.ACC_ANNOTATION) != 0) {
            signature.append("@interface ");
        } else if ((apiClass.access() & Opcodes.ACC_ENUM) != 0) {
            signature.append("enum ");
        } else if ((apiClass.access() & Opcodes.ACC_RECORD) != 0) {
            signature.append("record ");
        } else if ((apiClass.access() & Opcodes.ACC_INTERFACE) != 0) {
            signature.append("interface ");
        } else {
            signature.append("class ");
        }
        signature.append(apiClass.name());
        if (apiClass.superName() != null && !"java/lang/Object".equals(apiClass.superName())) {
            signature.append(" extends ").append(apiClass.superName());
        }
        if (!apiClass.interfaces().isEmpty()) {
            signature.append((apiClass.access() & Opcodes.ACC_INTERFACE) != 0
                            ? " extends " : " implements ")
                    .append(String.join(", ", apiClass.interfaces()));
        }
        if (apiClass.signature() != null) {
            signature.append(" [").append(apiClass.signature()).append(']');
        }
        return signature.toString();
    }

    private static String memberSignature(MemberApi member, boolean method) {
        StringBuilder signature = new StringBuilder();
        appendModifiers(signature, member.access(), method ? METHOD_FLAGS : FIELD_FLAGS);
        if (method) {
            signature.append("<init>".equals(member.name()) ? "constructor " : "method ")
                    .append(member.name()).append(member.descriptor());
        } else {
            signature.append("field ").append(member.name()).append(':')
                    .append(member.descriptor());
            if (member.constantValue() != null) {
                signature.append(" = ").append(member.constantValue());
            }
        }
        if (member.signature() != null) {
            signature.append(" [").append(member.signature()).append(']');
        }
        if (!member.exceptions().isEmpty()) {
            signature.append(" throws ").append(String.join(", ", member.exceptions()));
        }
        return signature.toString();
    }

    private static void appendModifiers(StringBuilder result, int access, int flags) {
        String[] names = {
                (access & Opcodes.ACC_PUBLIC) != 0 ? "public" : "",
                (access & Opcodes.ACC_PROTECTED) != 0 ? "protected" : "",
                (access & Opcodes.ACC_PRIVATE) != 0 ? "private" : "",
                (access & Opcodes.ACC_STATIC) != 0 ? "static" : "",
                (access & Opcodes.ACC_FINAL) != 0 ? "final" : "",
                (access & Opcodes.ACC_ABSTRACT) != 0 ? "abstract" : "",
                (access & Opcodes.ACC_SYNCHRONIZED) != 0 ? "synchronized" : "",
                (access & Opcodes.ACC_NATIVE) != 0 ? "native" : "",
                (access & Opcodes.ACC_STRICT) != 0 ? "strictfp" : "",
                (access & Opcodes.ACC_VOLATILE) != 0 ? "volatile" : "",
                (access & Opcodes.ACC_TRANSIENT) != 0 ? "transient" : "",
                (access & Opcodes.ACC_BRIDGE) != 0 ? "bridge" : "",
                (access & Opcodes.ACC_VARARGS) != 0 ? "varargs" : "",
                (access & Opcodes.ACC_ENUM) != 0 ? "enum" : "",
                (access & Opcodes.ACC_SYNTHETIC) != 0 ? "synthetic" : ""
        };
        for (String name : names) {
            if (!name.isEmpty() && (flags & flagFor(name)) != 0) {
                result.append(name).append(' ');
            }
        }
    }

    private static int flagFor(String name) {
        return switch (name) {
            case "public" -> Opcodes.ACC_PUBLIC;
            case "protected" -> Opcodes.ACC_PROTECTED;
            case "private" -> Opcodes.ACC_PRIVATE;
            case "static" -> Opcodes.ACC_STATIC;
            case "final" -> Opcodes.ACC_FINAL;
            case "abstract" -> Opcodes.ACC_ABSTRACT;
            case "synchronized" -> Opcodes.ACC_SYNCHRONIZED;
            case "native" -> Opcodes.ACC_NATIVE;
            case "strictfp" -> Opcodes.ACC_STRICT;
            case "volatile" -> Opcodes.ACC_VOLATILE;
            case "transient" -> Opcodes.ACC_TRANSIENT;
            case "bridge" -> Opcodes.ACC_BRIDGE;
            case "varargs" -> Opcodes.ACC_VARARGS;
            case "enum" -> Opcodes.ACC_ENUM;
            case "synthetic" -> Opcodes.ACC_SYNTHETIC;
            default -> 0;
        };
    }

    private static boolean equal(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }

    private static final class ClassData {
        private String name;
        private int access;
        private String signature;
        private String superName;
        private List<String> interfaces = List.of();
        private final Map<MemberKey, MemberApi> methods = new HashMap<>();
        private final Map<MemberKey, MemberApi> fields = new HashMap<>();
    }

    private record ClassApi(
            String name,
            int access,
            String signature,
            String superName,
            List<String> interfaces,
            Map<MemberKey, MemberApi> methods,
            Map<MemberKey, MemberApi> fields) {
        private ClassApi {
            interfaces = List.copyOf(interfaces);
            methods = Map.copyOf(methods);
            fields = Map.copyOf(fields);
        }
    }

    private record MemberKey(String name, String descriptor) {
    }

    private record MemberApi(
            String name,
            String descriptor,
            int access,
            String signature,
            List<String> exceptions,
            String constantValue) {
        private MemberApi {
            exceptions = List.copyOf(exceptions);
        }
    }
}
