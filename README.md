# SmartDepTest: Dependency and API Impact Analysis

SmartDepTest finds a Maven dependency change, compares the dependency's old and new public APIs, and checks whether Java application source uses potentially incompatible APIs.

## In simple terms

1. Enter the path to a Maven project that is a Git repository.
2. The program finds its `pom.xml` files.
3. It searches first-parent Git history for commits that changed those files.
4. For each commit, it compares the POM before and after that commit.
5. It compares old/new dependency JAR APIs.
6. It checks changed APIs against application source and reports evidence-based potential impact.

**Important:** it does not always compare only the two newest commits. It skips POM commits that do not change a dependency and keeps searching older POM-changing commits until it finds a dependency change or reaches the available history.

## Requirements

- JDK 21 or newer with the Java compiler available.
- Maven available as `mvn` in `PATH`.
- Git available in `PATH` for Component 1.
- Network access only when a required artifact or Maven plugin is not already cached locally.

## Build and test

Run these commands from the project folder in PowerShell:

```powershell
mvn test
```

If Maven is not on `PATH`, run it using its full path:

```powershell
& "C:\Program Files\apache-maven-3.9.16\bin\mvn.cmd" test
```

Tests include dependency-history detection and offline synthetic-JAR tests for API changes, actual method usage, multi-module source, and impact classification. Test JARs are generated locally; they do not access Maven Central.

## Run the program

```powershell
mvn package
java -cp target/classes smartdeptest.Main
```

When prompted, enter the path to the Maven Git project you want to inspect. The existing Component 1 report appears first; API and application-impact analysis follows it.

For both dependency versions, the analyzer invokes Maven from the target project's directory and passes the POM that declared the change. Maven applies that project's repositories, parent configuration, user settings, mirrors, and local cache. Maven copies resolved JARs to a temporary analysis directory; SmartDepTest does not guess the local-repository path or alter the target POM. For semantic application analysis, it asks Maven for each module's compile classpath. The application is not executed.

For a large repository, increase the Git command timeout from its 120-second default:

```powershell
java -Dsmartdeptest.git.timeout.seconds=300 -cp target/classes smartdeptest.Main
```

## Pipeline guides

- [Architecture guide](docs/architecture/README.md): Component 1–3 responsibilities and source map.
- [Detailed design guide](docs/detailed-design/README.md): Git detection, API comparison, source usage, and impact rules.

## Current limits

The Component 1 parser resolves properties declared in the same POM and versions declared in that POM's dependency management. It does not build Maven's complete effective POM, so parent inheritance, profiles, and imported BOMs are not included. API impact analysis supports JAR dependencies and standard Maven `src/main/java` source roots. It is static evidence, not a runtime or test result, and it does not select regression tests.

The history search has no fixed commit-count limit. A shallow Git clone only contains part of the history; if no change is found in the available portion, the program asks you to fetch more history.
