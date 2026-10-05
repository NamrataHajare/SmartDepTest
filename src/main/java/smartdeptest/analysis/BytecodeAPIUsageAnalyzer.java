package smartdeptest.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import smartdeptest.analysis.APIUsageResult.Classification;
import smartdeptest.analysis.APIUsageResult.DependencyImpact;
import smartdeptest.analysis.APIUsageResult.ApplicationCall;
import smartdeptest.analysis.APIUsageResult.UsageFinding;
import smartdeptest.analysis.APIUsageResult.UsageLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.jar.JarFile;

final class BytecodeAPIUsageAnalyzer {
    private final MavenModuleClasspathResolver classpathResolver;
    private final ApplicationModuleScanner moduleScanner;

    BytecodeAPIUsageAnalyzer(MavenModuleClasspathResolver classpathResolver) {
        this.classpathResolver = classpathResolver;
        this.moduleScanner = new ApplicationModuleScanner(classpathResolver);
    }

    APIUsageResult analyze(APIChangeResult apiChanges, Path projectDirectory) {
        List<DependencyApiResult> analyzable = apiChanges.dependencies().stream()
                .filter(result -> result.status() == DependencyApiResult.Status.ANALYZED)
                .filter(result -> !result.changes().isEmpty())
                .toList();
        if (analyzable.isEmpty()) {
            List<DependencyImpact> impacts = apiChanges.dependencies().stream()
                    .map(result -> impactWithoutBytecodeScan(result,
                            result.status() == DependencyApiResult.Status.ANALYZED
                                    ? Classification.NO_IDENTIFIED_IMPACT : Classification.ANALYSIS_UNAVAILABLE))
                .toList();
            try {
                    UsageIndex usageIndex = indexUsages(analyzable, projectDirectory);
                    return new APIUsageResult(apiChanges.projectPath(), impacts, usageIndex.applicationCalls());
                } catch (Exception exception) {
                    String message = exception.getMessage() == null
                            ? exception.getClass().getSimpleName() : exception.getMessage();
                    System.out.println("Application call graph discovery unavailable: " + message);
                    return new APIUsageResult(apiChanges.projectPath(), impacts);
            }
        }

        try {
            UsageIndex usageIndex = indexUsages(analyzable, projectDirectory);
            List<DependencyImpact> impacts = apiChanges.dependencies().stream()
                    .map(result -> buildImpact(result, usageIndex)).toList();
            return new APIUsageResult(apiChanges.projectPath(), impacts, usageIndex.applicationCalls());
        } catch (Exception exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            return new APIUsageResult(apiChanges.projectPath(), apiChanges.dependencies().stream()
                    .map(result -> unavailableImpact(result, message)).toList());
        }
    }

    private UsageIndex indexUsages(List<DependencyApiResult> analyzable, Path projectDirectory) throws IOException {
        List<ApplicationModule> modules = moduleScanner.discover(projectDirectory);
        if (modules.isEmpty()) {
            throw new IOException("No Maven application modules with resolvable compiled output "
                    + "or JVM source roots were found.");
        }

        Map<ApiReference, List<ImpactReference>> allReferences = buildReferenceIndex(analyzable);
        Map<ImpactReference, Set<UsageLocation>> locations = new HashMap<>();
        Set<Path> scannedClasses = new HashSet<>();
        Set<ApplicationMethod> applicationMethods = new LinkedHashSet<>();
        Set<ApplicationCall> applicationCalls = new LinkedHashSet<>();
        long asmNanos = 0;

        for (ApplicationModule module : modules) {
            List<DependencyApiResult> moduleDependencies = analyzable.stream()
                .filter(dependency -> dependency.dependencyManagement()
                    || isDeclaredInModule(dependency, module)).toList();
            List<Path> classpath = moduleDependencies.isEmpty() ? List.of() : classpathResolver.resolve(module);
            Map<ApiReference, List<ImpactReference>> moduleReferences = moduleDependencies.isEmpty()
                    ? Map.of() : referencesOnModuleClasspath(moduleDependencies, allReferences, classpath);
                List<Path> existingClassesDirectories = module.classesDirectories().stream()
                    .filter(Files::isDirectory).toList();
                if (existingClassesDirectories.size() != module.classesDirectories().size()) {
                List<Path> missingDirectories = module.classesDirectories().stream()
                    .filter(path -> !Files.isDirectory(path)).toList();
                System.out.printf("Application bytecode output missing for module %s: %s%n",
                    module.moduleDirectory(), missingDirectories);
                }
                if (existingClassesDirectories.isEmpty()) {
                if (moduleReferences.isEmpty()) continue;
                throw new IOException("Application bytecode is missing from Maven output directories: "
                    + module.classesDirectories()
                        + ". Compile the relevant Maven module before API impact analysis.");
            }

            long scanStarted = System.nanoTime();
            List<Path> hierarchyClasspath = new ArrayList<>(classpath);
            moduleDependencies.stream().map(DependencyApiResult::oldArtifactPath)
                    .filter(path -> !path.isBlank()).map(Path::of).filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize()).forEach(hierarchyClasspath::add);
            int classCount = scanClasses(module, moduleReferences, locations, scannedClasses,
                    new ClassHierarchy(hierarchyClasspath.stream().distinct().toList()),
                    applicationMethods, applicationCalls);
            asmNanos += System.nanoTime() - scanStarted;
            if (classCount == 0 && !moduleReferences.isEmpty()) {
                throw new IOException("No application class files were found under Maven output directories "
                        + existingClassesDirectories);
            }
        }
        System.out.printf("ASM analysis completed in %d ms.%n", asmNanos / 1_000_000);
        List<ApplicationCall> callsToApplicationMethods = applicationCalls.stream()
                .filter(call -> applicationMethods.contains(new ApplicationMethod(call.targetClassName(),
                        call.targetMethodName(), call.targetMethodDescriptor())))
                .toList();
        return new UsageIndex(locations, callsToApplicationMethods);
    }

    private static boolean isDeclaredInModule(DependencyApiResult dependency, ApplicationModule module) {
        String changedPom = Path.of(dependency.pomPath()).normalize().toString().replace('\\', '/');
        if (changedPom.equals("pom.xml")) return true;
        String modulePom = module.projectDirectory().relativize(module.pomFile())
                .toString().replace('\\', '/');
        return modulePom.equals(changedPom);
    }

    private static Map<ApiReference, List<ImpactReference>> buildReferenceIndex(
            List<DependencyApiResult> dependencies) {
        Map<ApiReference, List<ImpactReference>> index = new HashMap<>();
        for (DependencyApiResult dependency : dependencies) {
            for (ApiChange change : dependency.changes()) {
                ImpactReference impact = new ImpactReference(dependency, change);
                switch (change.kind()) {
                    case CLASS_ADDED, CLASS_REMOVED, CLASS_MODIFIED -> addReference(index,
                            new ApiReference(ReferenceKind.CLASS, change.className(), "", ""), impact);
                    case METHOD_ADDED, METHOD_REMOVED, METHOD_MODIFIED -> {
                        addDescriptors(index, impact, ReferenceKind.METHOD, change,
                                change.kind() != ApiChange.Kind.METHOD_ADDED,
                                change.kind() != ApiChange.Kind.METHOD_REMOVED);
                        if (change.kind() == ApiChange.Kind.METHOD_ADDED && change.potentiallyIncompatible()) {
                            addReference(index, new ApiReference(ReferenceKind.CLASS,
                                    change.className(), "", ""), impact);
                        }
                    }
                    case FIELD_ADDED, FIELD_REMOVED, FIELD_MODIFIED -> addDescriptors(index, impact,
                            ReferenceKind.FIELD, change, change.kind() != ApiChange.Kind.FIELD_ADDED,
                            change.kind() != ApiChange.Kind.FIELD_REMOVED);
                }
            }
        }
        return index;
    }

    private static void addDescriptors(Map<ApiReference, List<ImpactReference>> index,
                                       ImpactReference impact, ReferenceKind kind, ApiChange change,
                                       boolean includeOld, boolean includeNew) {
        if (includeOld && !change.oldDescriptor().isBlank()) {
            addReference(index, reference(kind, change, change.oldDescriptor()), impact);
        }
        if (includeNew && !change.newDescriptor().isBlank()) {
            addReference(index, reference(kind, change, change.newDescriptor()), impact);
        }
    }

    private static ApiReference reference(ReferenceKind kind, ApiChange change, String descriptor) {
        return new ApiReference(kind, change.className(), change.memberName(), descriptor);
    }

    private static void addReference(Map<ApiReference, List<ImpactReference>> index,
                                     ApiReference reference, ImpactReference impact) {
        List<ImpactReference> matches = index.computeIfAbsent(reference, ignored -> new ArrayList<>());
        if (!matches.contains(impact)) matches.add(impact);
    }

    private static Map<ApiReference, List<ImpactReference>> referencesOnModuleClasspath(
            List<DependencyApiResult> dependencies,
            Map<ApiReference, List<ImpactReference>> allReferences,
            List<Path> classpath) {
        Set<DependencyApiResult> dependenciesOnClasspath = new HashSet<>();
        for (DependencyApiResult dependency : dependencies) {
            Path artifactSuffix = MavenArtifactResolver.artifactPathSuffix(dependency.groupId(),
                    dependency.artifactId(), dependency.newVersion(), dependency.newClassifier());
            if (classpath.stream().map(path -> path.toAbsolutePath().normalize())
                    .anyMatch(path -> path.endsWith(artifactSuffix))) {
                dependenciesOnClasspath.add(dependency);
            }
        }
        if (dependenciesOnClasspath.isEmpty()) return Map.of();

        Map<ApiReference, List<ImpactReference>> moduleReferences = new HashMap<>();
        allReferences.forEach((reference, impacts) -> {
            List<ImpactReference> relevant = impacts.stream()
                    .filter(impact -> dependenciesOnClasspath.contains(impact.dependency())).toList();
            if (!relevant.isEmpty()) moduleReferences.put(reference, relevant);
        });
        return moduleReferences;
    }

    private static int scanClasses(ApplicationModule module,
                                   Map<ApiReference, List<ImpactReference>> references,
                                   Map<ImpactReference, Set<UsageLocation>> locations,
                                   Set<Path> scannedClasses,
                                   ClassHierarchy hierarchy,
                                   Set<ApplicationMethod> applicationMethods,
                                   Set<ApplicationCall> applicationCalls) throws IOException {
        int count = 0;
        for (Path classesDirectory : module.classesDirectories()) {
            if (!Files.isDirectory(classesDirectory)) continue;
            try (Stream<Path> paths = Files.walk(classesDirectory)) {
                for (Path classFile : paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".class")).toList()) {
                    Path normalized = classFile.toAbsolutePath().normalize();
                    if (!scannedClasses.add(normalized)) continue;
                    count++;
                    try (InputStream input = Files.newInputStream(normalized)) {
                        new ClassReader(input).accept(new UsageClassVisitor(references, locations, hierarchy,
                                        applicationMethods, applicationCalls),
                                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    } catch (IOException | RuntimeException exception) {
                        throw new IOException("Unable to analyze application class " + normalized + ": "
                                + exception.getMessage(), exception);
                    }
                }
            }
        }
        return count;
    }

    private static DependencyImpact buildImpact(DependencyApiResult dependency, UsageIndex usageIndex) {
        List<UsageFinding> findings = dependency.changes().stream().map(change -> {
            Set<UsageLocation> matches = usageIndex.locations().getOrDefault(
                    new ImpactReference(dependency, change), Set.of());
            return new UsageFinding(change, !matches.isEmpty(), List.copyOf(matches));
        }).toList();
        boolean usedChangedApi = findings.stream().anyMatch(UsageFinding::used);
        Classification classification = dependency.status() == DependencyApiResult.Status.UNAVAILABLE
                ? Classification.ANALYSIS_UNAVAILABLE
                : usedChangedApi ? Classification.POTENTIAL_IMPACT : Classification.NO_IDENTIFIED_IMPACT;
        return new DependencyImpact(dependency.dependencyKey(), dependency.oldVersion(), dependency.newVersion(),
            dependency.oldClassifier(), dependency.newClassifier(), dependency.pomPath(),
            dependency.oldScope(), dependency.newScope(), dependency.oldType(), dependency.newType(),
            dependency.dependencyManagement(), classification, dependency.message(), findings);
    }

    private static DependencyImpact impactWithoutBytecodeScan(DependencyApiResult dependency,
                                                               Classification classification) {
        List<UsageFinding> findings = dependency.changes().stream()
                .map(change -> new UsageFinding(change, false, List.of())).toList();
        return new DependencyImpact(dependency.dependencyKey(), dependency.oldVersion(), dependency.newVersion(),
            dependency.oldClassifier(), dependency.newClassifier(), dependency.pomPath(),
            dependency.oldScope(), dependency.newScope(), dependency.oldType(), dependency.newType(),
            dependency.dependencyManagement(), classification, dependency.message(), findings);
    }

    private static DependencyImpact unavailableImpact(DependencyApiResult dependency, String message) {
        return new DependencyImpact(dependency.dependencyKey(), dependency.oldVersion(), dependency.newVersion(),
            dependency.oldClassifier(), dependency.newClassifier(), dependency.pomPath(),
            dependency.oldScope(), dependency.newScope(), dependency.oldType(), dependency.newType(),
            dependency.dependencyManagement(), Classification.ANALYSIS_UNAVAILABLE, message, List.of());
    }

    private static void record(Map<ApiReference, List<ImpactReference>> references,
                               Map<ImpactReference, Set<UsageLocation>> locations,
                               ApiReference reference, UsageLocation location) {
        for (ImpactReference impact : references.getOrDefault(reference, List.of())) {
            locations.computeIfAbsent(impact, ignored -> new LinkedHashSet<>()).add(location);
        }
    }

    private static void recordClassType(Map<ApiReference, List<ImpactReference>> references,
                                         Map<ImpactReference, Set<UsageLocation>> locations,
                                         String internalName, UsageLocation location) {
        if (internalName == null || internalName.isBlank()) return;
        String normalized = internalName.startsWith("[")
                ? Type.getType(internalName).getElementType().getInternalName() : internalName;
        record(references, locations, new ApiReference(ReferenceKind.CLASS, normalized, "", ""), location);
    }

    private static void recordDescriptorTypes(Map<ApiReference, List<ImpactReference>> references,
                                              Map<ImpactReference, Set<UsageLocation>> locations,
                                              String descriptor, UsageLocation location) {
        if (descriptor == null || descriptor.isBlank() || descriptor.charAt(0) == '<') return;
        Type type = descriptor.startsWith("(") ? Type.getMethodType(descriptor) : Type.getType(descriptor);
        if (type.getSort() == Type.METHOD) {
            for (Type argument : type.getArgumentTypes()) recordType(references, locations, argument, location);
            recordType(references, locations, type.getReturnType(), location);
        } else {
            recordType(references, locations, type, location);
        }
    }

    private static void recordType(Map<ApiReference, List<ImpactReference>> references,
                                   Map<ImpactReference, Set<UsageLocation>> locations,
                                   Type type, UsageLocation location) {
        if (type.getSort() == Type.ARRAY) type = type.getElementType();
        if (type.getSort() == Type.OBJECT) {
            recordClassType(references, locations, type.getInternalName(), location);
        }
    }

    private static void recordHandle(Map<ApiReference, List<ImpactReference>> references,
                                     Map<ImpactReference, Set<UsageLocation>> locations,
                                     ClassHierarchy hierarchy, Handle handle, UsageLocation location) {
        int tag = handle.getTag();
        if (tag >= Opcodes.H_GETFIELD && tag <= Opcodes.H_PUTSTATIC) {
            recordMember(references, locations, hierarchy, ReferenceKind.FIELD, handle.getOwner(),
                    handle.getName(), handle.getDesc(), location);
        } else {
            recordMember(references, locations, hierarchy, ReferenceKind.METHOD, handle.getOwner(),
                    handle.getName(), handle.getDesc(), location);
        }
    }

    private static void recordMember(Map<ApiReference, List<ImpactReference>> references,
                                     Map<ImpactReference, Set<UsageLocation>> locations,
                                     ClassHierarchy hierarchy, ReferenceKind kind,
                                     String owner, String name, String descriptor, UsageLocation location) {
        record(references, locations, new ApiReference(kind, owner, name, descriptor), location);
        if (kind == ReferenceKind.METHOD && "<init>".equals(name)) return;
        try {
            hierarchy.recordInherited(owner, new MemberKey(kind, name, descriptor), references, locations, location);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private enum ReferenceKind { CLASS, METHOD, FIELD }

    private record ApiReference(ReferenceKind kind, String owner, String name, String descriptor) {}

    private record MemberKey(ReferenceKind kind, String name, String descriptor) {}

    private record ClassInfo(List<String> parents, Set<MemberKey> members) {}

    private record ImpactReference(DependencyApiResult dependency, ApiChange change) {}

    private record UsageIndex(Map<ImpactReference, Set<UsageLocation>> locations,
                              List<ApplicationCall> applicationCalls) {}

    private record ApplicationMethod(String className, String methodName, String descriptor) {}

    private static final class UsageClassVisitor extends ClassVisitor {
        private final Map<ApiReference, List<ImpactReference>> references;
        private final Map<ImpactReference, Set<UsageLocation>> locations;
        private final ClassHierarchy hierarchy;
        private final Set<ApplicationMethod> applicationMethods;
        private final Set<ApplicationCall> applicationCalls;
        private String className;

        private UsageClassVisitor(Map<ApiReference, List<ImpactReference>> references,
                                  Map<ImpactReference, Set<UsageLocation>> locations,
                                  ClassHierarchy hierarchy,
                                  Set<ApplicationMethod> applicationMethods,
                                  Set<ApplicationCall> applicationCalls) {
            super(Opcodes.ASM9);
            this.references = references;
            this.locations = locations;
            this.hierarchy = hierarchy;
            this.applicationMethods = applicationMethods;
            this.applicationCalls = applicationCalls;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            className = name;
            UsageLocation classLocation = new UsageLocation(className, "<class>", "", "CLASS_DECLARATION");
            recordClassType(references, locations, superName, classLocation);
            if (interfaces != null) {
                for (String interfaceName : interfaces) {
                    recordClassType(references, locations, interfaceName, classLocation);
                }
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            recordDescriptorTypes(references, locations, descriptor,
                    new UsageLocation(className, "<field>", descriptor, "FIELD_DECLARATION"));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            applicationMethods.add(new ApplicationMethod(className, name, descriptor));
            UsageLocation methodLocation = new UsageLocation(className, name, descriptor, "METHOD_DESCRIPTOR");
            recordDescriptorTypes(references, locations, descriptor, methodLocation);
            return new MethodVisitor(Opcodes.ASM9) {
                private UsageLocation location(String instructionType) {
                    return new UsageLocation(className, name, descriptor, instructionType);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName,
                                            String methodDescriptor, boolean isInterface) {
                    String instruction = switch (opcode) {
                        case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
                        case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
                        case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
                        case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
                        default -> "METHOD_INSN_" + opcode;
                    };
                    UsageLocation use = location(instruction);
                    recordMember(references, locations, hierarchy, ReferenceKind.METHOD,
                            owner, methodName, methodDescriptor, use);
                    recordClassType(references, locations, owner, use);
                    recordDescriptorTypes(references, locations, methodDescriptor, use);
                    String callInstruction = applicationCallInstruction(opcode);
                    if (callInstruction != null) {
                        applicationCalls.add(new ApplicationCall(className, name, descriptor, owner,
                                methodName, methodDescriptor, callInstruction));
                    }
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                    String instruction = switch (opcode) {
                        case Opcodes.GETFIELD -> "GETFIELD";
                        case Opcodes.PUTFIELD -> "PUTFIELD";
                        case Opcodes.GETSTATIC -> "GETSTATIC";
                        case Opcodes.PUTSTATIC -> "PUTSTATIC";
                        default -> "FIELD_INSN_" + opcode;
                    };
                    UsageLocation use = location(instruction);
                        recordMember(references, locations, hierarchy, ReferenceKind.FIELD,
                            owner, fieldName, fieldDescriptor, use);
                    recordClassType(references, locations, owner, use);
                    recordDescriptorTypes(references, locations, fieldDescriptor, use);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    String instruction = switch (opcode) {
                        case Opcodes.NEW -> "NEW";
                        case Opcodes.ANEWARRAY -> "ANEWARRAY";
                        case Opcodes.CHECKCAST -> "CHECKCAST";
                        case Opcodes.INSTANCEOF -> "INSTANCEOF";
                        default -> "TYPE_INSN_" + opcode;
                    };
                    recordClassType(references, locations, type, location(instruction));
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type type) {
                        recordType(references, locations, type, location("LDC_TYPE"));
                    } else if (value instanceof Handle handle) {
                        recordHandle(references, locations, hierarchy, handle, location("LDC_HANDLE"));
                    }
                }

                @Override
                public void visitInvokeDynamicInsn(String invokedName, String invokedDescriptor,
                                                   Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                    UsageLocation use = location("INVOKEDYNAMIC");
                    recordDescriptorTypes(references, locations, invokedDescriptor, use);
                    recordHandle(references, locations, hierarchy, bootstrapMethodHandle, use);
                    for (Object argument : bootstrapMethodArguments) {
                        if (argument instanceof Handle handle) {
                            recordHandle(references, locations, hierarchy, handle, use);
                        }
                        else if (argument instanceof Type type) recordType(references, locations, type, use);
                    }
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                    recordDescriptorTypes(references, locations, descriptor, location("MULTIANEWARRAY"));
                }
            };
        }

        private static String applicationCallInstruction(int opcode) {
            return switch (opcode) {
                case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
                case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
                case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
                case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
                default -> null;
            };
        }
    }

    private static final class ClassHierarchy {
        private final List<Path> classpath;
        private final Map<String, ClassInfo> cache = new HashMap<>();
        private final Set<String> missing = new HashSet<>();

        private ClassHierarchy(List<Path> classpath) {
            this.classpath = classpath;
        }

        private void recordInherited(String owner, MemberKey member,
                                     Map<ApiReference, List<ImpactReference>> references,
                                     Map<ImpactReference, Set<UsageLocation>> locations,
                                     UsageLocation location) throws IOException {
            recordInherited(owner, member, references, locations, location, new HashSet<>());
        }

        private void recordInherited(String owner, MemberKey member,
                                     Map<ApiReference, List<ImpactReference>> references,
                                     Map<ImpactReference, Set<UsageLocation>> locations,
                                     UsageLocation location, Set<String> visited) throws IOException {
            if (!visited.add(owner)) return;
            ClassInfo info = load(owner);
            if (info == null || info.members().contains(member)) return;
            for (String parent : info.parents()) {
                record(references, locations, new ApiReference(member.kind(), parent,
                        member.name(), member.descriptor()), location);
                recordInherited(parent, member, references, locations, location, visited);
            }
        }

        private ClassInfo load(String owner) throws IOException {
            ClassInfo cachedInfo = cache.get(owner);
            if (cachedInfo != null) return cachedInfo;
            if (missing.contains(owner)) return null;

            String classEntry = owner + ".class";
            for (Path entry : classpath) {
                Path normalized = entry.toAbsolutePath().normalize();
                if (Files.isDirectory(normalized)) {
                    Path classFile = normalized.resolve(classEntry);
                    if (!Files.isRegularFile(classFile)) continue;
                    try (InputStream input = Files.newInputStream(classFile)) {
                        ClassInfo info = readClassInfo(input);
                        cache.put(owner, info);
                        return info;
                    }
                }
                if (!Files.isRegularFile(normalized)) continue;
                try (JarFile jar = new JarFile(normalized.toFile())) {
                    var jarEntry = jar.getJarEntry(classEntry);
                    if (jarEntry == null) continue;
                    try (InputStream input = jar.getInputStream(jarEntry)) {
                        ClassInfo info = readClassInfo(input);
                        cache.put(owner, info);
                        return info;
                    }
                }
            }
            missing.add(owner);
            return null;
        }

        private static ClassInfo readClassInfo(InputStream input) throws IOException {
            List<String> parents = new ArrayList<>();
            Set<MemberKey> members = new HashSet<>();
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    if (superName != null) parents.add(superName);
                    if (interfaces != null) parents.addAll(List.of(interfaces));
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    members.add(new MemberKey(ReferenceKind.METHOD, name, descriptor));
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    members.add(new MemberKey(ReferenceKind.FIELD, name, descriptor));
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new ClassInfo(List.copyOf(parents), Set.copyOf(members));
        }
    }
}