package smartdeptest.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenModuleClasspathResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesCustomCompilerOutputFromMavenEffectivePom() throws Exception {
        Path project = temporaryDirectory.resolve("custom-output-project");
        Path moduleDirectory = project.resolve("module-a");
        Path modulePom = moduleDirectory.resolve("pom.xml");
        Path customOutput = moduleDirectory.resolve("build/compiled-app").toAbsolutePath().normalize();
        Files.createDirectories(moduleDirectory);
        Files.createDirectories(project);
        Files.writeString(modulePom, "<project/>", StandardCharsets.UTF_8);
        MavenModuleClasspathResolver resolver = new MavenModuleClasspathResolver((workingDirectory, arguments) -> {
            String outputArgument = arguments.stream().filter(argument -> argument.startsWith("-Doutput="))
                    .findFirst().orElseThrow();
            String effectivePom = "<project><build><outputDirectory>" + moduleDirectory.resolve("build/classes")
                    + "</outputDirectory><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                    + "<configuration><outputDirectory>" + customOutput
                    + "</outputDirectory></configuration></plugin></plugins></build></project>";
            Files.writeString(Path.of(outputArgument.substring("-Doutput=".length())), effectivePom,
                    StandardCharsets.UTF_8);
            return "effective model resolved";
        });
        ApplicationModule module = new ApplicationModule(project, moduleDirectory, modulePom,
                List.of(), true);

        List<Path> outputs = resolver.resolveOutputDirectories(module);

        assertEquals(List.of(customOutput), outputs);
    }

        @Test
        void discoversUncompiledModuleWithConfiguredMainSourceDirectory() throws Exception {
                Path project = temporaryDirectory.resolve("custom-source-project");
                Path moduleDirectory = project.resolve("module-a");
                Path modulePom = moduleDirectory.resolve("pom.xml");
                Path customSource = moduleDirectory.resolve("src/application/java");
                Path customOutput = moduleDirectory.resolve("build/application-classes");
                Files.createDirectories(customSource);
                Files.createDirectories(project);
                Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
                Files.writeString(modulePom, "<project><build><sourceDirectory>src/application/java</sourceDirectory>"
                                + "<outputDirectory>build/application-classes</outputDirectory></build></project>",
                                StandardCharsets.UTF_8);
                MavenModuleClasspathResolver resolver = new MavenModuleClasspathResolver() {
                        @Override
                        List<Path> resolveOutputDirectories(ApplicationModule module) {
                                return List.of(customOutput);
                        }
                };

                List<ApplicationModule> modules = new ApplicationModuleScanner(resolver).discover(project);

                assertEquals(1, modules.size());
                assertTrue(modules.get(0).hasMainSources());
                assertEquals(List.of(customOutput), modules.get(0).classesDirectories());
                assertFalse(Files.exists(customOutput));
        }

    @Test
    void resolvesModuleClasspathThroughRootReactorAndAlsoMakesDependencies() throws Exception {
        Path project = temporaryDirectory.resolve("project");
        Path moduleDirectory = project.resolve("sponge");
        Path sourceDirectory = moduleDirectory.resolve("src/main/java");
        Path modulePom = moduleDirectory.resolve("pom.xml");
        Path rootPom = project.resolve("pom.xml");
        Files.createDirectories(sourceDirectory);
        Files.writeString(rootPom, "<project><modules><module>sponge</module></modules></project>",
                StandardCharsets.UTF_8);
        Files.writeString(modulePom, "<project/>", StandardCharsets.UTF_8);
        Path classpathJar = temporaryDirectory.resolve("dependency.jar");
        Files.writeString(classpathJar, "jar", StandardCharsets.UTF_8);
        List<Invocation> invocations = new ArrayList<>();
        MavenModuleClasspathResolver resolver = new MavenModuleClasspathResolver((workingDirectory, arguments) -> {
            invocations.add(new Invocation(workingDirectory, arguments));
            String outputOption = arguments.stream().filter(argument -> argument.startsWith("-Dmdep.outputFile="))
                    .findFirst().orElseThrow();
            Files.writeString(Path.of(outputOption.substring("-Dmdep.outputFile=".length())),
                    classpathJar.toString(), StandardCharsets.UTF_8);
            return "classpath resolved";
        });
        ApplicationModule module = new ApplicationModule(project, moduleDirectory, modulePom,
                moduleDirectory.resolve("target/classes"));

        List<Path> classpath = resolver.resolve(module);

        assertEquals(1, invocations.size());
        assertEquals(project.toAbsolutePath().normalize(), invocations.get(0).workingDirectory());
        List<String> arguments = invocations.get(0).arguments();
        assertFalse(arguments.contains("-U"));
        assertEquals(rootPom.toAbsolutePath().normalize().toString(),
                arguments.get(arguments.indexOf("-f") + 1));
        assertEquals("sponge", arguments.get(arguments.indexOf("-pl") + 1));
        assertTrue(arguments.contains("-am"));
        assertTrue(classpath.contains(classpathJar.toAbsolutePath().normalize()));
    }

    @Test
    void fallsBackToStandaloneModulePomWhenRootIsNotAReactor() throws Exception {
        Path project = temporaryDirectory.resolve("project");
        Path moduleDirectory = project.resolve("module-a");
        Path sourceDirectory = moduleDirectory.resolve("src/main/java");
        Path modulePom = moduleDirectory.resolve("pom.xml");
        Files.createDirectories(sourceDirectory);
        Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(modulePom, "<project/>", StandardCharsets.UTF_8);
        List<Invocation> invocations = new ArrayList<>();
        MavenModuleClasspathResolver resolver = new MavenModuleClasspathResolver((workingDirectory, arguments) -> {
            invocations.add(new Invocation(workingDirectory, arguments));
            if (arguments.contains("-am")) throw new IOException("Not a Maven reactor");
            String outputOption = arguments.stream().filter(argument -> argument.startsWith("-Dmdep.outputFile="))
                    .findFirst().orElseThrow();
            Files.writeString(Path.of(outputOption.substring("-Dmdep.outputFile=".length())), "",
                    StandardCharsets.UTF_8);
            return "classpath resolved";
        });
        ApplicationModule module = new ApplicationModule(project, moduleDirectory, modulePom,
                moduleDirectory.resolve("target/classes"));

        resolver.resolve(module);

        assertEquals(2, invocations.size());
        assertEquals(rootPom(project).toString(), invocations.get(0).arguments()
                .get(invocations.get(0).arguments().indexOf("-f") + 1));
        assertEquals(modulePom.toString(), invocations.get(1).arguments()
                .get(invocations.get(1).arguments().indexOf("-f") + 1));
        assertTrue(!invocations.get(1).arguments().contains("-am"));
    }

        @Test
        void includesRootReactorAndStandaloneErrorsWhenBothFail() throws Exception {
                Path project = temporaryDirectory.resolve("project");
                Path moduleDirectory = project.resolve("sponge");
                Path sourceDirectory = moduleDirectory.resolve("src/main/java");
                Path modulePom = moduleDirectory.resolve("pom.xml");
                Files.createDirectories(sourceDirectory);
                Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
                Files.writeString(modulePom, "<project/>", StandardCharsets.UTF_8);
                MavenModuleClasspathResolver resolver = new MavenModuleClasspathResolver((workingDirectory, arguments) -> {
                        if (arguments.contains("-am")) throw new IOException("[ERROR] Could not select module sponge");
                        throw new IOException("[ERROR] changeskin.core:3.1-SNAPSHOT was not found");
                });
                ApplicationModule module = new ApplicationModule(project, moduleDirectory, modulePom,
                                moduleDirectory.resolve("target/classes"));

                IOException exception = assertThrows(IOException.class, () -> resolver.resolve(module));

                assertTrue(exception.getMessage().contains("Could not select module sponge"));
                assertTrue(exception.getMessage().contains("changeskin.core:3.1-SNAPSHOT was not found"));
        }

        @Test
        void retriesCachedClasspathFailureWithUpdateFlagOnlyOnce() throws Exception {
                Path project = temporaryDirectory.resolve("project");
                Path moduleDirectory = project.resolve("module-a");
                Path sourceDirectory = moduleDirectory.resolve("src/main/java");
                Path modulePom = moduleDirectory.resolve("pom.xml");
                Files.createDirectories(sourceDirectory);
                Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
                Files.writeString(modulePom, "<project/>", StandardCharsets.UTF_8);
                List<Invocation> invocations = new ArrayList<>();
                MavenModuleClasspathResolver resolver = new MavenModuleClasspathResolver((workingDirectory, arguments) -> {
                        invocations.add(new Invocation(workingDirectory, arguments));
                        if (!arguments.contains("-U")) {
                                throw new IOException("Resolution failure was cached after a previous attempt");
                        }
                        String outputOption = arguments.stream().filter(argument -> argument.startsWith("-Dmdep.outputFile="))
                                        .findFirst().orElseThrow();
                        Files.writeString(Path.of(outputOption.substring("-Dmdep.outputFile=".length())), "",
                                        StandardCharsets.UTF_8);
                        return "retried with update flag";
                });
                ApplicationModule module = new ApplicationModule(project, moduleDirectory, modulePom,
                                moduleDirectory.resolve("target/classes"));

                resolver.resolve(module);

                assertEquals(2, invocations.size());
                assertFalse(invocations.get(0).arguments().contains("-U"));
                assertTrue(invocations.get(1).arguments().contains("-U"));
        }

    private static Path rootPom(Path project) {
        return project.resolve("pom.xml").toAbsolutePath().normalize();
    }

    private record Invocation(Path workingDirectory, List<String> arguments) {
        private Invocation {
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
            arguments = List.copyOf(arguments);
        }
    }
}