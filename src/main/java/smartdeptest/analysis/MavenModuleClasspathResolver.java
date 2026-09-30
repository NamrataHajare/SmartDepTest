package smartdeptest.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class MavenModuleClasspathResolver {
    private final MavenArtifactResolver.MavenInvoker mavenInvoker;

    MavenModuleClasspathResolver() {
        this(MavenCommandRunner::run);
    }

    MavenModuleClasspathResolver(MavenArtifactResolver.MavenInvoker mavenInvoker) {
        this.mavenInvoker = mavenInvoker;
    }

    List<Path> resolve(ApplicationModule module) throws IOException {
        Path output = Files.createTempFile("smartdeptest-classpath-", ".txt");
        try {
            Files.deleteIfExists(output);
            IOException reactorFailure = null;
            Path reactorPom = module.projectDirectory().resolve("pom.xml");
            if (!reactorPom.equals(module.pomFile()) && Files.isRegularFile(reactorPom)) {
                String moduleSelector = module.projectDirectory().relativize(module.moduleDirectory())
                        .toString().replace('\\', '/');
                try {
                    System.out.println("Resolving Maven classpath through root reactor: "
                        + reactorPom + " (module " + moduleSelector + ")...");
                    runBuildClasspath(module.projectDirectory(), reactorPom, output,
                            List.of("-pl", moduleSelector, "-am"));
                } catch (IOException exception) {
                    reactorFailure = exception;
                    System.out.println("Root reactor classpath resolution failed: " + summarize(exception));
                    Files.deleteIfExists(output);
                }
            }
            if (!Files.isRegularFile(output) && !Files.exists(output)) {
                try {
                    runBuildClasspath(module.projectDirectory(), module.pomFile(), output, List.of());
                } catch (IOException standaloneFailure) {
                    if (reactorFailure != null) {
                        standaloneFailure.addSuppressed(reactorFailure);
                    }
                    String reactorError = reactorFailure == null ? "not attempted" : summarize(reactorFailure);
                    throw new IOException("Unable to resolve Maven compile classpath for module POM "
                            + module.pomFile() + ". Root reactor attempt: " + reactorError
                            + ". Standalone attempt: " + summarize(standaloneFailure),
                            standaloneFailure);
                }
            }
            List<Path> entries = new ArrayList<>();
            if (Files.isRegularFile(output)) {
                String classpath = Files.readString(output).trim();
                if (!classpath.isBlank()) {
                    for (String entry : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                        Path path = Path.of(entry);
                        if (Files.exists(path)) entries.add(path.toAbsolutePath().normalize());
                    }
                }
            }
            Path classes = module.moduleDirectory().resolve("target/classes");
            if (Files.isDirectory(classes)) entries.add(classes.toAbsolutePath().normalize());
            return entries.stream().distinct().toList();
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private void runBuildClasspath(Path workingDirectory, Path pomFile, Path output,
                                   List<String> selectionArguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("mvn");
        command.add("-f");
        command.add(pomFile.toString());
        command.addAll(selectionArguments);
        command.add("org.apache.maven.plugins:maven-dependency-plugin:3.7.1:build-classpath");
        command.add("-DincludeScope=compile");
        command.add("-Dmdep.outputFile=" + output);
        mavenInvoker.run(workingDirectory, command);
        if (!Files.isRegularFile(output)) {
            throw new IOException("Maven completed without writing the requested classpath file: " + output);
        }
    }

    private static String summarize(IOException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        List<String> errors = message.lines().map(String::trim)
                .filter(line -> line.startsWith("[ERROR]")).toList();
        String summary = errors.isEmpty() ? message : String.join("; ", errors);
        return summary.length() <= 1200 ? summary : summary.substring(0, 1200) + "...";
    }
}