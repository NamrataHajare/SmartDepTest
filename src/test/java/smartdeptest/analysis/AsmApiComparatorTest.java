package smartdeptest.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsmApiComparatorTest {
    @TempDir
    Path temporaryDirectory;

    private final AsmApiComparator comparator = new AsmApiComparator();

    @Test
    void unchangedApiAndMissingReferencedTypesDoNotRequireClasspathResolution() throws Exception {
        byte[] unchanged = classBytes(
                "api/Unchanged", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "java/lang/Object", List.of("missing/Interface"),
                List.of(new MethodDef(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                        "use", "(Lmissing/Dependency;)Lmissing/Dependency;")),
                List.of(new FieldDef(Opcodes.ACC_PUBLIC, "dependency", "Lmissing/Dependency;")));
        Path oldJar = jar("old.jar", List.of(new Entry("api/Unchanged.class", unchanged)));
        Path newJar = jar("new.jar", List.of(
                new Entry("api/Unchanged.class", unchanged),
                new Entry("duplicate/SecondEntry.class", unchanged)));

        assertTrue(comparator.compare(oldJar, newJar).isEmpty());
    }

    @Test
    void comparesClassesMethodsConstructorsFieldsAndVisibility() throws Exception {
        byte[] oldService = classBytes(
                "api/Service", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                "java/lang/Object", List.of("api/OldContract"),
                List.of(
                        new MethodDef(Opcodes.ACC_PUBLIC, "<init>", "()V"),
                        new MethodDef(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "process", "(I)V"),
                        new MethodDef(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "removed", "()V"),
                        new MethodDef(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "lowered", "()V"),
                        new MethodDef(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "raised", "()V")),
                List.of(
                        new FieldDef(Opcodes.ACC_PUBLIC, "count", "I"),
                        new FieldDef(Opcodes.ACC_PUBLIC, "removedField", "J"),
                        new FieldDef(Opcodes.ACC_PROTECTED, "loweredField", "I")));
        byte[] newService = classBytes(
                "api/Service", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                "api/Base", List.of(),
                List.of(
                        new MethodDef(Opcodes.ACC_PUBLIC, "<init>", "(I)V"),
                        new MethodDef(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "process", "(J)V"),
                        new MethodDef(Opcodes.ACC_PRIVATE, "lowered", "()V"),
                        new MethodDef(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "raised", "()V"),
                        new MethodDef(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "added", "()V")),
                List.of(
                        new FieldDef(Opcodes.ACC_PUBLIC, "count", "J"),
                        new FieldDef(Opcodes.ACC_PUBLIC, "newField", "I"),
                        new FieldDef(0, "loweredField", "I")));
        byte[] goneClass = classBytes("api/Gone", Opcodes.ACC_PUBLIC,
                "java/lang/Object", List.of(), List.of(), List.of());
        byte[] addedClass = classBytes("api/Added", Opcodes.ACC_PUBLIC,
                "java/lang/Object", List.of(), List.of(), List.of());
        Path oldJar = jar("old.jar", List.of(
                new Entry("api/Service.class", oldService),
                new Entry("api/Gone.class", goneClass)));
        Path newJar = jar("new.jar", List.of(
                new Entry("api/Service.class", newService),
                new Entry("api/Added.class", addedClass)));

        List<ApiChange> changes = comparator.compare(oldJar, newJar);

        ApiChange classChange = change(changes, ApiChange.Kind.CLASS_MODIFIED, "api/Service", "");
        assertTrue(classChange.oldSignature().contains("api/OldContract"));
        assertTrue(classChange.newSignature().contains("extends api/Base"));
        assertTrue(classChange.potentiallyIncompatible());
        assertEquals(ApiChange.Kind.CLASS_REMOVED,
                change(changes, ApiChange.Kind.CLASS_REMOVED, "api/Gone", "").kind());
        assertFalse(change(changes, ApiChange.Kind.CLASS_ADDED, "api/Added", "").potentiallyIncompatible());

        ApiChange changedConstructor = change(changes, ApiChange.Kind.METHOD_MODIFIED,
                "api/Service", "<init>");
        assertEquals("()V", changedConstructor.oldDescriptor());
        assertEquals("(I)V", changedConstructor.newDescriptor());
        assertEquals(ApiChange.Kind.METHOD_REMOVED,
                change(changes, ApiChange.Kind.METHOD_REMOVED, "api/Service", "removed").kind());
        ApiChange changedMethod = change(changes, ApiChange.Kind.METHOD_MODIFIED,
                "api/Service", "process");
        assertEquals("(I)V", changedMethod.oldDescriptor());
        assertEquals("(J)V", changedMethod.newDescriptor());
        assertTrue(changedMethod.potentiallyIncompatible());
        assertTrue(change(changes, ApiChange.Kind.METHOD_ADDED, "api/Service", "added")
                .potentiallyIncompatible());
        assertTrue(change(changes, ApiChange.Kind.METHOD_MODIFIED, "api/Service", "lowered")
                .potentiallyIncompatible());
        assertFalse(change(changes, ApiChange.Kind.METHOD_MODIFIED, "api/Service", "raised")
                .potentiallyIncompatible());

        ApiChange changedField = change(changes, ApiChange.Kind.FIELD_MODIFIED, "api/Service", "count");
        assertEquals("I", changedField.oldDescriptor());
        assertEquals("J", changedField.newDescriptor());
        assertTrue(changedField.potentiallyIncompatible());
        assertEquals(ApiChange.Kind.FIELD_REMOVED,
                change(changes, ApiChange.Kind.FIELD_REMOVED, "api/Service", "removedField").kind());
        assertEquals(ApiChange.Kind.FIELD_ADDED,
                change(changes, ApiChange.Kind.FIELD_ADDED, "api/Service", "newField").kind());
        assertTrue(change(changes, ApiChange.Kind.FIELD_MODIFIED, "api/Service", "loweredField")
                .potentiallyIncompatible());
    }

    @Test
    void doesNotPairAmbiguousOverloadDescriptorChanges() throws Exception {
        Path oldJar = jar("old.jar", List.of(new Entry("api/Overloads.class",
                classBytes("api/Overloads", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                        "java/lang/Object", List.of(),
                        List.of(
                                new MethodDef(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "call", "(I)V"),
                                new MethodDef(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "call", "(J)V")),
                        List.of()))));
        Path newJar = jar("new.jar", List.of(new Entry("api/Overloads.class",
                classBytes("api/Overloads", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                        "java/lang/Object", List.of(),
                        List.of(
                                new MethodDef(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "call", "(F)V"),
                                new MethodDef(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "call", "(D)V")),
                        List.of()))));

        List<ApiChange> changes = comparator.compare(oldJar, newJar);

        assertEquals(2, changes.stream()
                .filter(change -> change.kind() == ApiChange.Kind.METHOD_REMOVED).count());
        assertEquals(2, changes.stream()
                .filter(change -> change.kind() == ApiChange.Kind.METHOD_ADDED).count());
        assertFalse(changes.stream()
                .anyMatch(change -> change.kind() == ApiChange.Kind.METHOD_MODIFIED));
    }

    @Test
    void ordinaryAddedMethodIsNotMarkedIncompatible() throws Exception {
        Path oldJar = jar("old.jar", List.of(new Entry("api/Service.class",
                classBytes("api/Service", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                        "java/lang/Object", List.of(), List.of(), List.of()))));
        Path newJar = jar("new.jar", List.of(new Entry("api/Service.class",
                classBytes("api/Service", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                        "java/lang/Object", List.of(),
                        List.of(new MethodDef(Opcodes.ACC_PUBLIC, "added", "()V")), List.of()))));

        assertFalse(change(comparator.compare(oldJar, newJar),
                ApiChange.Kind.METHOD_ADDED, "api/Service", "added").potentiallyIncompatible());
    }

    @Test
    void classVisibilityReductionRemovesItsExposedMembers() throws Exception {
        Path oldJar = jar("old.jar", List.of(new Entry("api/Hidden.class",
                classBytes("api/Hidden", Opcodes.ACC_PUBLIC,
                        "java/lang/Object", List.of(),
                        List.of(new MethodDef(Opcodes.ACC_PUBLIC, "call", "()V")), List.of()))));
        Path newJar = jar("new.jar", List.of(new Entry("api/Hidden.class",
                classBytes("api/Hidden", 0,
                        "java/lang/Object", List.of(),
                        List.of(new MethodDef(Opcodes.ACC_PUBLIC, "call", "()V")), List.of()))));

        List<ApiChange> changes = comparator.compare(oldJar, newJar);

        assertTrue(change(changes, ApiChange.Kind.CLASS_MODIFIED, "api/Hidden", "")
                .potentiallyIncompatible());
        assertEquals("()V", change(changes, ApiChange.Kind.METHOD_REMOVED,
                "api/Hidden", "call").oldDescriptor());
    }

    @Test
    void unreadableClassFileIsAnExplicitComparisonFailure() throws Exception {
        Path oldJar = jar("old.jar", List.of(new Entry("api/Broken.class", new byte[]{1, 2, 3})));
        Path newJar = jar("new.jar", List.of());

        assertThrows(IOException.class, () -> comparator.compare(oldJar, newJar));
    }

    private ApiChange change(
            List<ApiChange> changes, ApiChange.Kind kind, String className, String memberName) {
        return changes.stream()
                .filter(change -> change.kind() == kind
                        && change.className().equals(className)
                        && change.memberName().equals(memberName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + kind + " for " + className + "#" + memberName + " in " + changes));
    }

    private Path jar(String name, List<Entry> entries) throws IOException {
        Path path = temporaryDirectory.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Entry entry : entries) {
                output.putNextEntry(new JarEntry(entry.name()));
                output.write(entry.bytes());
                output.closeEntry();
            }
        }
        return path;
    }

    private static byte[] classBytes(
            String name, int access, String superName, List<String> interfaces,
            List<MethodDef> methods, List<FieldDef> fields) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, access, name, null, superName, interfaces.toArray(String[]::new));
        for (FieldDef field : fields) {
            writer.visitField(field.access(), field.name(), field.descriptor(), null, null).visitEnd();
        }
        for (MethodDef method : methods) {
            MethodVisitor visitor = writer.visitMethod(
                    method.access(), method.name(), method.descriptor(), null, null);
            if ((method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                visitor.visitCode();
                if ("<init>".equals(method.name())) {
                    visitor.visitVarInsn(Opcodes.ALOAD, 0);
                    visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
                    visitor.visitInsn(Opcodes.RETURN);
                } else {
                    addDefaultReturn(visitor, Type.getReturnType(method.descriptor()));
                }
                visitor.visitMaxs(0, 0);
            }
            visitor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addDefaultReturn(MethodVisitor visitor, Type returnType) {
        switch (returnType.getSort()) {
            case Type.VOID -> visitor.visitInsn(Opcodes.RETURN);
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                visitor.visitInsn(Opcodes.ICONST_0);
                visitor.visitInsn(Opcodes.IRETURN);
            }
            case Type.LONG -> {
                visitor.visitInsn(Opcodes.LCONST_0);
                visitor.visitInsn(Opcodes.LRETURN);
            }
            case Type.FLOAT -> {
                visitor.visitInsn(Opcodes.FCONST_0);
                visitor.visitInsn(Opcodes.FRETURN);
            }
            case Type.DOUBLE -> {
                visitor.visitInsn(Opcodes.DCONST_0);
                visitor.visitInsn(Opcodes.DRETURN);
            }
            case Type.ARRAY, Type.OBJECT -> {
                visitor.visitInsn(Opcodes.ACONST_NULL);
                visitor.visitInsn(Opcodes.ARETURN);
            }
            default -> throw new IllegalArgumentException("Unsupported return descriptor: " + returnType);
        }
    }

    private record Entry(String name, byte[] bytes) {
    }

    private record MethodDef(int access, String name, String descriptor) {
    }

    private record FieldDef(int access, String name, String descriptor) {
    }
}
