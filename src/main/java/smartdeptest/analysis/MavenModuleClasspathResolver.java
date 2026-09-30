package smartdeptest.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class MavenModuleClasspathResolver {
    List<Path> resolve(ApplicationModule module) throws IOException {
        Path output = Files.createTempFile("smartdeptest-classpath-", ".txt");
        try {
            Files.deleteIfExists(output);
            MavenCommandRunner.run(module.moduleDirectory(), List.of("mvn", "-q", "-f",
                    module.pomFile().toString(),
                    "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:build-classpath",
                    "-DincludeScope=compile", "-Dmdep.outputFile=" + output));
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
}