package smartdeptest.analysis;

import com.sun.source.util.JavacTask;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

final class ApiSurfaceReader {
    record Member(ApiChange.Kind kind, String className, String memberName,
                  String signature, List<String> parameterTypes, boolean potentiallyIncompatible) {}

    private static final Set<Modifier> VISIBILITY = Set.of(Modifier.PUBLIC, Modifier.PROTECTED);

    Map<String, Member> read(Path jar) throws IOException {
        return read(jar, List.of(jar));
    }

    Map<String, Member> read(Path jar, List<Path> classpath) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("A JDK with the Java compiler is required for API analysis.");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
             JarFile jarFile = new JarFile(jar.toFile())) {
            JavaFileObject probe = new SimpleJavaFileObject(URI.create("string:///SmartDepTestProbe.java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return "final class SmartDepTestProbe {}";
                }
            };
                String classpathOption = classpath.stream().map(Path::toString)
                    .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
                JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none", "-classpath", classpathOption), null, List.of(probe));
            task.parse();
            task.analyze();
                List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                    .map(diagnostic -> diagnostic.getMessage(null)).toList();
                if (!errors.isEmpty()) {
                throw new IOException("The artifact API could not be fully resolved: " + errors.get(0));
                }
            Elements elements = task.getElements();
            Types types = task.getTypes();
            Map<String, Member> members = new HashMap<>();
            for (var entry : jarFile.stream().filter(item -> !item.isDirectory())
                    .filter(item -> item.getName().endsWith(".class"))
                    .filter(item -> !item.getName().startsWith("META-INF/versions/"))
                    .filter(item -> !item.getName().equals("module-info.class"))
                    .filter(item -> !item.getName().endsWith("/package-info.class"))
                    .toList()) {
                String binaryName = entry.getName().substring(0, entry.getName().length() - 6)
                        .replace('/', '.');
                TypeElement type = elements.getTypeElement(binaryName.replace('$', '.'));
                if (type == null || !isVisible(type)) continue;
                addType(members, type, elements);
                for (Element enclosed : type.getEnclosedElements()) {
                    if (!isVisible(enclosed)) continue;
                    if (enclosed.getKind() == ElementKind.METHOD || enclosed.getKind() == ElementKind.CONSTRUCTOR) {
                        addMethod(members, type, (ExecutableElement) enclosed, types);
                    } else if (enclosed.getKind() == ElementKind.FIELD
                            || enclosed.getKind() == ElementKind.ENUM_CONSTANT) {
                        addField(members, type, (VariableElement) enclosed, types);
                    }
                }
            }
            return Map.copyOf(members);
        } catch (RuntimeException exception) {
            throw new IOException("Unable to inspect public API in " + jar + ": " + exception.getMessage(), exception);
        }
    }

    private static void addType(Map<String, Member> members, TypeElement type, Elements elements) {
        String name = type.getQualifiedName().toString();
        String parent = type.getSuperclass().getKind().isPrimitive() ? "" : type.getSuperclass().toString();
        List<String> interfaces = type.getInterfaces().stream().map(Object::toString).sorted().toList();
        List<? extends TypeParameterElement> typeParameters = type.getTypeParameters();
        String signature = type.getModifiers() + " " + type.getKind() + " " + name
            + typeParameters + " extends " + parent + " implements " + interfaces;
        boolean incompatible = !parent.isBlank() && !parent.equals("java.lang.Object") || !interfaces.isEmpty();
        members.put("C:" + name, new Member(ApiChange.Kind.CLASS_MODIFIED, name, "", signature,
                List.of(), incompatible));
    }

    private static void addMethod(Map<String, Member> members, TypeElement owner,
                                  ExecutableElement method, Types types) {
        String className = owner.getQualifiedName().toString();
        String name = method.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : method.getSimpleName().toString();
        List<String> parameters = method.getParameters().stream()
            .map(parameter -> parameter.asType().toString()).toList();
        List<String> erasedParameters = method.getParameters().stream()
            .map(parameter -> types.erasure(parameter.asType()).toString()).toList();
        String returnType = method.getKind() == ElementKind.CONSTRUCTOR ? "" : method.getReturnType().toString();
        List<String> thrown = method.getThrownTypes().stream().map(Object::toString).toList();
        String signature = method.getModifiers() + " " + method.getTypeParameters() + " " + name
            + "(" + String.join(", ", parameters) + ")"
                + (returnType.isEmpty() ? "" : ": " + returnType)
            + (thrown.isEmpty() ? "" : " throws " + String.join(", ", thrown))
            + (method.getDefaultValue() == null ? "" : " default " + method.getDefaultValue());
        String identity = "M:" + className + ":" + name + "(" + String.join(",", erasedParameters) + ")";
        members.put(identity, new Member(ApiChange.Kind.METHOD_MODIFIED, className, name,
            signature, erasedParameters, method.getModifiers().contains(Modifier.ABSTRACT)));
    }

    private static void addField(Map<String, Member> members, TypeElement owner,
                                 VariableElement field, Types types) {
        String className = owner.getQualifiedName().toString();
        String name = field.getSimpleName().toString();
        String signature = field.getModifiers() + " " + field.asType() + " " + name
            + (field.getConstantValue() == null ? "" : " = " + field.getConstantValue());
        members.put("F:" + className + ":" + name,
                new Member(ApiChange.Kind.FIELD_MODIFIED, className, name, signature, List.of(), true));
    }

    private static boolean isVisible(Element element) {
        if (element.getModifiers().stream().noneMatch(VISIBILITY::contains)) return false;
        for (Element owner = element.getEnclosingElement(); owner instanceof TypeElement; owner = owner.getEnclosingElement()) {
            if (owner.getModifiers().stream().noneMatch(VISIBILITY::contains)) return false;
        }
        return true;
    }
}