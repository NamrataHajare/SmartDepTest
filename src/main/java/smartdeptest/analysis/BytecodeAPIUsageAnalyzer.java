
package smartdeptest.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import smartdeptest.analysis.APIUsageResult.ApplicationCall;
import smartdeptest.analysis.APIUsageResult.AnalysisSummary;
import smartdeptest.analysis.APIUsageResult.Classification;
import smartdeptest.analysis.APIUsageResult.DependencyImpact;
import smartdeptest.analysis.APIUsageResult.UsageFinding;
import smartdeptest.analysis.APIUsageResult.UsageLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

final class BytecodeAPIUsageAnalyzer {

    private final MavenModuleClasspathResolver classpathResolver;
    private final List<String> diagnostics = new ArrayList<>();
    private final ApplicationModuleScanner moduleScanner;

    BytecodeAPIUsageAnalyzer(MavenModuleClasspathResolver classpathResolver) {
        this.classpathResolver = classpathResolver;
        this.moduleScanner = new ApplicationModuleScanner(classpathResolver, diagnostics);
    }

    APIUsageResult analyze(APIChangeResult apiChanges, Path projectDirectory) {
        List<DependencyApiResult> analyzable = apiChanges.dependencies().stream()
                .filter(d -> d.status() == DependencyApiResult.Status.ANALYZED)
                .filter(d -> !d.changes().isEmpty())
                .toList();

        try {
            UsageIndex index = indexUsages(analyzable, projectDirectory);

            List<DependencyImpact> impacts = apiChanges.dependencies().stream()
                    .map(d -> buildImpact(d, index))
                    .toList();

                return new APIUsageResult(apiChanges.projectPath(), impacts, index.applicationCalls(),
                    index.summary(), diagnostics);

        } catch (Exception e) {
            String message = messageOf(e);
            addDiagnostic("Application bytecode analysis failed: " + message);

            List<DependencyImpact> impacts = apiChanges.dependencies().stream()
                    .map(d -> unavailableImpact(d, message))
                    .toList();

                return new APIUsageResult(apiChanges.projectPath(), impacts, List.of(),
                    AnalysisSummary.empty(), diagnostics);
        }
    }

    private UsageIndex indexUsages(
            List<DependencyApiResult> analyzable,
            Path projectDirectory) throws IOException {

        List<ApplicationModule> modules = moduleScanner.discover(projectDirectory);
        if (modules.isEmpty()) {
            throw new IOException("No Maven application modules were discovered.");
        }

        Map<ApiReference, List<ImpactReference>> allReferences = buildReferenceIndex(analyzable);

        Map<ImpactReference, Set<UsageLocation>> locations = new HashMap<>();
        Set<Path> scannedClasses = new HashSet<>();
        Set<Path> incompleteModules = new HashSet<>();
        Set<ApplicationMethod> applicationMethods = new LinkedHashSet<>();
        Set<ApplicationCall> applicationCalls = new LinkedHashSet<>();

        long started = System.nanoTime();
        int classFilesDiscovered = 0;
        int classFilesAnalyzed = 0;
        int classFileFailures = 0;
        int classDirectoryFailures = 0;
        int duplicateClassFilesSkipped = 0;

        for (ApplicationModule module : modules) {
            String modulePath = module.moduleDirectory()
                    .toAbsolutePath().normalize().toString();

            List<DependencyApiResult> moduleDependencies = analyzable.stream()
                    .filter(d -> d.dependencyManagement() || isDeclaredInModule(d, module))
                    .toList();

            List<Path> classpath;
            try {
                classpath = moduleDependencies.isEmpty()
                        ? List.of()
                        : classpathResolver.resolve(module);
            } catch (Exception e) {
                addDiagnostic("Could not resolve classpath for module "
                    + module.moduleDirectory() + ": " + messageOf(e));
                incompleteModules.add(module.pomFile().toAbsolutePath().normalize());
                continue;
            }

            Map<ApiReference, List<ImpactReference>> moduleReferences = moduleDependencies.isEmpty()
                    ? Map.of()
                    : referencesOnModuleClasspath(
                            moduleDependencies, allReferences, classpath, diagnostics);

            List<Path> classDirectories = module.classesDirectories().stream()
                    .filter(Files::isDirectory)
                    .toList();

            if (classDirectories.isEmpty()) {
                if (!moduleReferences.isEmpty()) {
                        addDiagnostic("No compiled application classes for module "
                            + module.moduleDirectory());
                    incompleteModules.add(module.pomFile().toAbsolutePath().normalize());
                }
                continue;
            }

            List<Path> hierarchyClasspath = new ArrayList<>(classpath);
            moduleDependencies.stream()
                    .map(DependencyApiResult::oldArtifactPath)
                    .filter(p -> p != null && !p.isBlank())
                    .map(Path::of)
                    .filter(Files::isRegularFile)
                    .map(p -> p.toAbsolutePath().normalize())
                    .forEach(hierarchyClasspath::add);

            ScanResult result = scanClasses(
                    module,
                    moduleReferences,
                    locations,
                    scannedClasses,
                    new ClassHierarchy(hierarchyClasspath.stream().distinct().toList(), diagnostics),
                    applicationMethods,
                    applicationCalls,
                    modulePath);

            classFilesDiscovered += result.discovered();
            classFilesAnalyzed += result.analyzed();
            classFileFailures += result.failed();
            classDirectoryFailures += result.directoryFailures();
            duplicateClassFilesSkipped += result.skipped();

            if (result.failed() > 0) {
                incompleteModules.add(module.pomFile().toAbsolutePath().normalize());
            }
        }

        List<ApplicationCall> callsToApplicationMethods = applicationCalls.stream()
                .filter(call -> applicationMethods.contains(new ApplicationMethod(
                        call.targetClassName(),
                        call.targetMethodName(),
                        call.targetMethodDescriptor())))
                .toList();

        AnalysisSummary summary = new AnalysisSummary(
            modules.size(), incompleteModules.size(), classFilesDiscovered,
            classFilesAnalyzed, classFileFailures, classDirectoryFailures,
            duplicateClassFilesSkipped);
        return new UsageIndex(locations, callsToApplicationMethods, incompleteModules, summary);
    }

    private static boolean isDeclaredInModule(
            DependencyApiResult dependency,
            ApplicationModule module) {

        String changedPom = normalizePom(dependency.pomPath());
        if (changedPom.equals("pom.xml")) {
            return true;
        }

        String modulePom = module.projectDirectory()
                .relativize(module.pomFile())
                .toString().replace('\\', '/');

        return modulePom.equals(changedPom);
    }

    private static String normalizePom(String pom) {
        if (pom == null || pom.isBlank()) {
            return "";
        }
        return Path.of(pom).normalize().toString().replace('\\', '/');
    }

    private static Map<ApiReference, List<ImpactReference>> buildReferenceIndex(
            List<DependencyApiResult> dependencies) {

        Map<ApiReference, List<ImpactReference>> index = new HashMap<>();

        for (DependencyApiResult dependency : dependencies) {
            for (ApiChange change : dependency.changes()) {
                ImpactReference impact = new ImpactReference(dependency, change);

                switch (change.kind()) {
                    case CLASS_ADDED, CLASS_REMOVED, CLASS_MODIFIED ->
                        addReference(index, new ApiReference(
                                ReferenceKind.CLASS, change.className(), "", ""), impact);

                    case METHOD_ADDED, METHOD_REMOVED, METHOD_MODIFIED -> {
                        addDescriptors(
                                index, impact, ReferenceKind.METHOD, change,
                                change.kind() != ApiChange.Kind.METHOD_ADDED,
                                change.kind() != ApiChange.Kind.METHOD_REMOVED);

                        if (change.kind() == ApiChange.Kind.METHOD_ADDED
                                && change.potentiallyIncompatible()) {
                            addReference(index, new ApiReference(
                                    ReferenceKind.CLASS, change.className(), "", ""), impact);
                        }
                    }

                    case FIELD_ADDED, FIELD_REMOVED, FIELD_MODIFIED ->
                        addDescriptors(
                                index, impact, ReferenceKind.FIELD, change,
                                change.kind() != ApiChange.Kind.FIELD_ADDED,
                                change.kind() != ApiChange.Kind.FIELD_REMOVED);
                }
            }
        }
        return index;
    }

    private static void addDescriptors(
            Map<ApiReference, List<ImpactReference>> index,
            ImpactReference impact,
            ReferenceKind kind,
            ApiChange change,
            boolean includeOld,
            boolean includeNew) {

        if (includeOld && !change.oldDescriptor().isBlank()) {
            addReference(index,
                    reference(kind, change, change.oldDescriptor()), impact);
        }
        if (includeNew && !change.newDescriptor().isBlank()) {
            addReference(index,
                    reference(kind, change, change.newDescriptor()), impact);
        }
    }

    private static ApiReference reference(
            ReferenceKind kind, ApiChange change, String descriptor) {
        return new ApiReference(kind, change.className(), change.memberName(), descriptor);
    }

    private static void addReference(
            Map<ApiReference, List<ImpactReference>> index,
            ApiReference reference,
            ImpactReference impact) {
        List<ImpactReference> list = index.computeIfAbsent(reference, ignored -> new ArrayList<>());
        if (!list.contains(impact)) {
            list.add(impact);
        }
    }

    private static Map<ApiReference, List<ImpactReference>> referencesOnModuleClasspath(
            List<DependencyApiResult> dependencies,
            Map<ApiReference, List<ImpactReference>> allReferences,
            List<Path> classpath,
            List<String> diagnostics) {

        Set<DependencyApiResult> matched = new HashSet<>();

        for (DependencyApiResult dependency : dependencies) {
            if (dependencyPresentOnClasspath(dependency, classpath)) {
                matched.add(dependency);
            } else {
                /*
                 * For a removed dependency there may be no new artifact on the
                 * current classpath. Its old artifact can still be used to
                 * identify API references made by application bytecode.
                 */
                String oldArtifact = dependency.oldArtifactPath();
                if (oldArtifact != null && !oldArtifact.isBlank()
                        && Files.isRegularFile(Path.of(oldArtifact))) {
                    matched.add(dependency);
                    String diagnostic = "Using the previous dependency artifact for API matching: "
                            + oldArtifact;
                    if (!diagnostics.contains(diagnostic)) {
                        diagnostics.add(diagnostic);
                    }
                }
            }
        }

        if (matched.isEmpty()) {
            return Map.of();
        }

        Map<ApiReference, List<ImpactReference>> result = new HashMap<>();
        allReferences.forEach((reference, impacts) -> {
            List<ImpactReference> relevant = impacts.stream()
                    .filter(i -> matched.contains(i.dependency()))
                    .toList();
            if (!relevant.isEmpty()) {
                result.put(reference, relevant);
            }
        });
        return result;
    }

    private static boolean dependencyPresentOnClasspath(
            DependencyApiResult dependency,
            List<Path> classpath) {

        String version = dependency.newVersion();
        String classifier = dependency.newClassifier();

        if (version == null || version.isBlank()) {
            version = dependency.oldVersion();
            classifier = dependency.oldClassifier();
        }

        if (version == null || version.isBlank()) {
            return false;
        }

        Path suffix = MavenArtifactResolver.artifactPathSuffix(
                dependency.groupId(), dependency.artifactId(), version, classifier);

        String type = dependency.newType();
        if (type == null || type.isBlank()) {
            type = dependency.oldType();
        }
        if (type == null || type.isBlank()) {
            type = "jar";
        }

        String expected = dependency.artifactId() + "-" + version
                + (classifier == null || classifier.isBlank() ? "" : "-" + classifier)
                + "." + type;

        for (Path entry : classpath) {
            if (entry == null) {
                continue;
            }
            Path normalized = entry.toAbsolutePath().normalize();
            if (normalized.endsWith(suffix)) {
                return true;
            }
            if (Files.isRegularFile(normalized)
                    && normalized.getFileName().toString().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private ScanResult scanClasses(
            ApplicationModule module,
            Map<ApiReference, List<ImpactReference>> references,
            Map<ImpactReference, Set<UsageLocation>> locations,
            Set<Path> scannedClasses,
            ClassHierarchy hierarchy,
            Set<ApplicationMethod> applicationMethods,
            Set<ApplicationCall> applicationCalls,
            String modulePath) {

        int discovered = 0;
        int scanned = 0;
        int failed = 0;
        int directoryFailures = 0;
        int skipped = 0;

        for (Path directory : module.classesDirectories()) {
            if (!Files.isDirectory(directory)) {
                continue;
            }

            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path file : paths
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".class"))
                        .toList()) {

                    Path normalized = file.toAbsolutePath().normalize();
                    discovered++;
                    if (!scannedClasses.add(normalized)) {
                        skipped++;
                        continue;
                    }

                    try (InputStream input = Files.newInputStream(normalized)) {
                        new ClassReader(input).accept(
                                new UsageClassVisitor(
                                        references, locations, hierarchy,
                                        applicationMethods, applicationCalls, modulePath),
                                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        scanned++;
                    } catch (IOException | RuntimeException e) {
                        failed++;
                        addDiagnostic("Could not analyze class file " + normalized + ": "
                                + messageOf(e));
                    }
                }
            } catch (IOException e) {
                directoryFailures++;
                addDiagnostic("Could not enumerate class directory " + directory + ": "
                        + messageOf(e));
            }
        }

        return new ScanResult(discovered, scanned, failed, directoryFailures, skipped);
    }

    private static DependencyImpact buildImpact(
            DependencyApiResult dependency,
            UsageIndex index) {

        List<UsageFinding> findings = dependency.changes().stream()
                .map(change -> {
                    Set<UsageLocation> matches = index.locations().getOrDefault(
                            new ImpactReference(dependency, change), Set.of());
                    return new UsageFinding(change, !matches.isEmpty(), List.copyOf(matches));
                })
                .toList();

        boolean used = findings.stream().anyMatch(UsageFinding::used);
        boolean incomplete = moduleAnalysisIncomplete(dependency, index.incompleteModules());

        Classification classification;
        String message = dependency.message();

        if (dependency.status() != DependencyApiResult.Status.ANALYZED) {
            classification = Classification.ANALYSIS_UNAVAILABLE;
        } else if (used) {
            classification = Classification.POTENTIAL_IMPACT;
        } else if (incomplete) {
            classification = Classification.ANALYSIS_UNAVAILABLE;
            message = appendMessage(message,
                    "Some application classes or modules could not be analyzed.");
        } else {
            classification = Classification.NO_IDENTIFIED_IMPACT;
        }

        return new DependencyImpact(
                dependency.dependencyKey(),
                dependency.oldVersion(),
                dependency.newVersion(),
                dependency.oldClassifier(),
                dependency.newClassifier(),
                dependency.pomPath(),
                dependency.oldScope(),
                dependency.newScope(),
                dependency.oldType(),
                dependency.newType(),
                dependency.dependencyManagement(),
                classification,
                message,
                findings);
    }

    private static boolean moduleAnalysisIncomplete(
            DependencyApiResult dependency, Set<Path> incompleteModules) {

        if (incompleteModules.isEmpty()) {
            return false;
        }

        String pom = normalizePom(dependency.pomPath());
        if (pom.equals("pom.xml")) {
            return true;
        }

        for (Path incomplete : incompleteModules) {
            if (incomplete.getFileName() != null
                    && incomplete.getFileName().toString().equals("pom.xml")) {
                String relative = incomplete.toString().replace('\\', '/');
                if (relative.endsWith("/" + pom) || relative.equals(pom)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String appendMessage(String current, String addition) {
        if (current == null || current.isBlank()) {
            return addition;
        }
        return current + " " + addition;
    }

    private static DependencyImpact unavailableImpact(
            DependencyApiResult dependency, String message) {
        return new DependencyImpact(
                dependency.dependencyKey(),
                dependency.oldVersion(),
                dependency.newVersion(),
                dependency.oldClassifier(),
                dependency.newClassifier(),
                dependency.pomPath(),
                dependency.oldScope(),
                dependency.newScope(),
                dependency.oldType(),
                dependency.newType(),
                dependency.dependencyManagement(),
                Classification.ANALYSIS_UNAVAILABLE,
                message,
                List.of());
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    private void addDiagnostic(String diagnostic) {
        if (!diagnostics.contains(diagnostic)) {
            diagnostics.add(diagnostic);
        }
    }

    private static void record(
            Map<ApiReference, List<ImpactReference>> references,
            Map<ImpactReference, Set<UsageLocation>> locations,
            ApiReference reference,
            UsageLocation location) {

        for (ImpactReference impact : references.getOrDefault(reference, List.of())) {
            locations.computeIfAbsent(impact, ignored -> new LinkedHashSet<>())
                    .add(location);
        }
    }

    private static void recordClassType(
            Map<ApiReference, List<ImpactReference>> references,
            Map<ImpactReference, Set<UsageLocation>> locations,
            String internalName,
            UsageLocation location) {

        if (internalName == null || internalName.isBlank()) {
            return;
        }

        String normalized = internalName;
        if (internalName.startsWith("[")) {
            Type element = Type.getType(internalName).getElementType();
            if (element.getSort() != Type.OBJECT) {
                return;
            }
            normalized = element.getInternalName();
        }

        record(references, locations,
                new ApiReference(ReferenceKind.CLASS, normalized, "", ""), location);
    }

    private static void recordDescriptorTypes(
            Map<ApiReference, List<ImpactReference>> references,
            Map<ImpactReference, Set<UsageLocation>> locations,
            String descriptor,
            UsageLocation location) {

        if (descriptor == null || descriptor.isBlank()) {
            return;
        }

        Type type;
        try {
            type = descriptor.startsWith("(")
                    ? Type.getMethodType(descriptor)
                    : Type.getType(descriptor);
        } catch (IllegalArgumentException e) {
            return;
        }

        if (type.getSort() == Type.METHOD) {
            for (Type argument : type.getArgumentTypes()) {
                recordType(references, locations, argument, location);
            }
            recordType(references, locations, type.getReturnType(), location);
        } else {
            recordType(references, locations, type, location);
        }
    }

    private static void recordType(
            Map<ApiReference, List<ImpactReference>> references,
            Map<ImpactReference, Set<UsageLocation>> locations,
            Type type,
            UsageLocation location) {

        while (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }

        if (type.getSort() == Type.OBJECT) {
            recordClassType(references, locations, type.getInternalName(), location);
        }
    }

    private static void recordHandle(
            Map<ApiReference, List<ImpactReference>> references,
            Map<ImpactReference, Set<UsageLocation>> locations,
            ClassHierarchy hierarchy,
            Handle handle,
            UsageLocation location) {

        int tag = handle.getTag();
        if (tag >= Opcodes.H_GETFIELD && tag <= Opcodes.H_PUTSTATIC) {
            recordMember(references, locations, hierarchy, ReferenceKind.FIELD,
                    handle.getOwner(), handle.getName(), handle.getDesc(), location);
        } else {
            recordMember(references, locations, hierarchy, ReferenceKind.METHOD,
                    handle.getOwner(), handle.getName(), handle.getDesc(), location);
        }
    }

    private static void recordMember(
            Map<ApiReference, List<ImpactReference>> references,
            Map<ImpactReference, Set<UsageLocation>> locations,
            ClassHierarchy hierarchy,
            ReferenceKind kind,
            String owner,
            String name,
            String descriptor,
            UsageLocation location) {

        record(references, locations,
                new ApiReference(kind, owner, name, descriptor), location);

        if (kind == ReferenceKind.METHOD && "<init>".equals(name)) {
            return;
        }

        try {
            hierarchy.recordInherited(owner, new MemberKey(kind, name, descriptor),
                    references, locations, location);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private enum ReferenceKind {
        CLASS, METHOD, FIELD
    }

    private record ApiReference(
            ReferenceKind kind, String owner, String name, String descriptor) {
    }

    private record MemberKey(
            ReferenceKind kind, String name, String descriptor) {
    }

    private record ClassInfo(
            List<String> parents, Set<MemberKey> members) {
    }

    private record ImpactReference(
            DependencyApiResult dependency, ApiChange change) {
    }

    private record ApplicationMethod(
            String className, String methodName, String descriptor) {
    }

    private record ScanResult(int discovered, int analyzed, int failed,
                              int directoryFailures, int skipped) {
    }

    private record UsageIndex(
            Map<ImpactReference, Set<UsageLocation>> locations,
            List<ApplicationCall> applicationCalls,
            Set<Path> incompleteModules,
            AnalysisSummary summary) {
    }

    private static final class UsageClassVisitor extends ClassVisitor {

        private final Map<ApiReference, List<ImpactReference>> references;
        private final Map<ImpactReference, Set<UsageLocation>> locations;
        private final ClassHierarchy hierarchy;
        private final Set<ApplicationMethod> applicationMethods;
        private final Set<ApplicationCall> applicationCalls;
        private final String modulePath;

        private String className;

        private UsageClassVisitor(
                Map<ApiReference, List<ImpactReference>> references,
                Map<ImpactReference, Set<UsageLocation>> locations,
                ClassHierarchy hierarchy,
                Set<ApplicationMethod> applicationMethods,
                Set<ApplicationCall> applicationCalls,
                String modulePath) {

            super(Opcodes.ASM9);
            this.references = references;
            this.locations = locations;
            this.hierarchy = hierarchy;
            this.applicationMethods = applicationMethods;
            this.applicationCalls = applicationCalls;
            this.modulePath = modulePath;
        }

        private UsageLocation use(
                String method, String descriptor, String instruction) {
            return new UsageLocation(
                    className, method, descriptor, instruction, modulePath);
        }

        @Override
        public void visit(
                int version, int access, String name, String signature,
                String superName, String[] interfaces) {

            className = name;
            UsageLocation classUse = use("<class>", "", "CLASS_DECLARATION");
            recordClassType(references, locations, superName, classUse);

            if (interfaces != null) {
                for (String interfaceName : interfaces) {
                    recordClassType(references, locations, interfaceName, classUse);
                }
            }
        }

        @Override
        public FieldVisitor visitField(
                int access, String name, String descriptor,
                String signature, Object value) {

            recordDescriptorTypes(references, locations, descriptor,
                    use("<field>", descriptor, "FIELD_DECLARATION"));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor,
                String signature, String[] exceptions) {

            applicationMethods.add(new ApplicationMethod(className, name, descriptor));
            recordDescriptorTypes(references, locations, descriptor,
                    use(name, descriptor, "METHOD_DESCRIPTOR"));

            return new MethodVisitor(Opcodes.ASM9) {

                private UsageLocation location(String instruction) {
                    return use(name, descriptor, instruction);
                }

                @Override
                public void visitMethodInsn(
                        int opcode, String owner, String methodName,
                        String methodDescriptor, boolean isInterface) {

                    String instruction = switch (opcode) {
                        case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
                        case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
                        case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
                        case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
                        default -> "METHOD_INSN_" + opcode;
                    };

                    UsageLocation location = location(instruction);
                    recordMember(references, locations, hierarchy,
                            ReferenceKind.METHOD, owner, methodName,
                            methodDescriptor, location);
                    recordClassType(references, locations, owner, location);
                    recordDescriptorTypes(references, locations, methodDescriptor, location);

                    if (opcode == Opcodes.INVOKEVIRTUAL
                            || opcode == Opcodes.INVOKEINTERFACE
                            || opcode == Opcodes.INVOKESTATIC
                            || opcode == Opcodes.INVOKESPECIAL) {
                        applicationCalls.add(new ApplicationCall(
                                className, name, descriptor,
                                owner, methodName, methodDescriptor,
                                instruction, modulePath));
                    }
                }

                @Override
                public void visitFieldInsn(
                        int opcode, String owner, String fieldName,
                        String fieldDescriptor) {

                    String instruction = switch (opcode) {
                        case Opcodes.GETFIELD -> "GETFIELD";
                        case Opcodes.PUTFIELD -> "PUTFIELD";
                        case Opcodes.GETSTATIC -> "GETSTATIC";
                        case Opcodes.PUTSTATIC -> "PUTSTATIC";
                        default -> "FIELD_INSN_" + opcode;
                    };

                    UsageLocation location = location(instruction);
                    recordMember(references, locations, hierarchy,
                            ReferenceKind.FIELD, owner, fieldName,
                            fieldDescriptor, location);
                    recordClassType(references, locations, owner, location);
                    recordDescriptorTypes(references, locations, fieldDescriptor, location);
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
                        recordHandle(references, locations, hierarchy, handle,
                                location("LDC_HANDLE"));
                    }
                }

                @Override
                public void visitInvokeDynamicInsn(
                        String invokedName, String invokedDescriptor,
                        Handle bootstrapMethodHandle, Object... arguments) {

                    UsageLocation location = location("INVOKEDYNAMIC");
                    recordDescriptorTypes(references, locations, invokedDescriptor, location);
                    recordHandle(references, locations, hierarchy,
                            bootstrapMethodHandle, location);

                    for (Object argument : arguments) {
                        if (argument instanceof Handle handle) {
                            recordHandle(references, locations, hierarchy, handle, location);
                        } else if (argument instanceof Type type) {
                            recordType(references, locations, type, location);
                        }
                    }
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                    recordDescriptorTypes(references, locations, descriptor,
                            location("MULTIANEWARRAY"));
                }
            };
        }
    }

    private static final class ClassHierarchy {

        private final List<Path> classpath;
        private final List<String> diagnostics;
        private final Map<String, ClassInfo> cache = new HashMap<>();
        private final Set<String> missing = new HashSet<>();

        private ClassHierarchy(List<Path> classpath, List<String> diagnostics) {
            this.classpath = classpath;
            this.diagnostics = diagnostics;
        }

        private void recordInherited(
                String owner,
                MemberKey member,
                Map<ApiReference, List<ImpactReference>> references,
                Map<ImpactReference, Set<UsageLocation>> locations,
                UsageLocation location) throws IOException {

            recordInherited(owner, member, references, locations, location, new HashSet<>());
        }

        private void recordInherited(
                String owner,
                MemberKey member,
                Map<ApiReference, List<ImpactReference>> references,
                Map<ImpactReference, Set<UsageLocation>> locations,
                UsageLocation location,
                Set<String> visited) throws IOException {

            if (owner == null || owner.isBlank() || !visited.add(owner)) {
                return;
            }

            ClassInfo info = load(owner);
            if (info == null || info.members().contains(member)) {
                return;
            }

            for (String parent : info.parents()) {
                record(references, locations,
                        new ApiReference(member.kind(), parent,
                                member.name(), member.descriptor()),
                        location);
                recordInherited(parent, member, references, locations, location, visited);
            }
        }

        private ClassInfo load(String owner) throws IOException {
            if (cache.containsKey(owner)) {
                return cache.get(owner);
            }
            if (missing.contains(owner)) {
                return null;
            }

            String entryName = owner + ".class";

            for (Path entry : classpath) {
                Path normalized = entry.toAbsolutePath().normalize();

                if (Files.isDirectory(normalized)) {
                    Path classFile = normalized.resolve(entryName);
                    if (!Files.isRegularFile(classFile)) {
                        continue;
                    }
                    try (InputStream input = Files.newInputStream(classFile)) {
                        ClassInfo info = readClassInfo(input);
                        cache.put(owner, info);
                        return info;
                    } catch (IOException | RuntimeException e) {
                        addHierarchyDiagnostic("Cannot read hierarchy class "
                            + classFile + ": " + messageOf(e));
                        continue;
                    }
                }

                if (!Files.isRegularFile(normalized)) {
                    continue;
                }

                try (JarFile jar = new JarFile(normalized.toFile())) {
                    var jarEntry = jar.getJarEntry(entryName);
                    if (jarEntry == null) {
                        continue;
                    }
                    try (InputStream input = jar.getInputStream(jarEntry)) {
                        ClassInfo info = readClassInfo(input);
                        cache.put(owner, info);
                        return info;
                    }
                } catch (IOException | RuntimeException e) {
                        addHierarchyDiagnostic("Cannot inspect hierarchy archive "
                            + normalized + ": " + messageOf(e));
                }
            }

            missing.add(owner);
            return null;
        }

        private void addHierarchyDiagnostic(String diagnostic) {
            if (!diagnostics.contains(diagnostic)) {
                diagnostics.add(diagnostic);
            }
        }

        private static ClassInfo readClassInfo(InputStream input) throws IOException {
            List<String> parents = new ArrayList<>();
            Set<MemberKey> members = new HashSet<>();

            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(
                        int version, int access, String name, String signature,
                        String superName, String[] interfaces) {
                    if (superName != null) {
                        parents.add(superName);
                    }
                    if (interfaces != null) {
                        parents.addAll(List.of(interfaces));
                    }
                }

                @Override
                public MethodVisitor visitMethod(
                        int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    members.add(new MemberKey(ReferenceKind.METHOD, name, descriptor));
                    return null;
                }

                @Override
                public FieldVisitor visitField(
                        int access, String name, String descriptor,
                        String signature, Object value) {
                    members.add(new MemberKey(ReferenceKind.FIELD, name, descriptor));
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            return new ClassInfo(List.copyOf(parents), Set.copyOf(members));
        }
    }
}