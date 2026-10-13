# SmartDepTest: Dependency and API Impact Analysis

SmartDepTest finds a Maven dependency change, compares the old and new dependency JARs directly with ASM, and uses ASM to locate changed API references in compiled application bytecode.

## In simple terms

1. Enter the path to a Maven project that is a Git repository.
2. The program finds its `pom.xml` files.
3. It searches first-parent Git history for commits that changed those files.
4. For each commit, it compares the POM before and after that commit.
5. It compares old/new dependency JAR APIs.
6. It asks Maven for each relevant module's effective compiled output directory, scans those class files, and reports application methods that reference changed APIs.
7. It builds application method-to-method `CALLS` edges and propagates direct impact backwards through callers.

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

Make sure Maven is installed and available on `PATH` before running the commands. The Maven installation path is machine-specific and is not configured in this project.

Tests include dependency-history detection and offline synthetic-JAR tests for API changes, removed methods, overloaded descriptors, changed fields, multiple application callers, and impact classification. Test JARs are generated locally; they do not access Maven Central.

## Run the program

```powershell
mvn compile
java -cp target/classes smartdeptest.Main
```

When prompted, enter the path to the Maven Git project you want to inspect. The existing Component 1 report appears first; API and application-impact analysis follows it.

The run also writes `target/smartdeptest-impact-graph.json` inside the analyzed project. The JSON contains dependency, changed API, application class, and application method nodes; evidence-backed edges; direct and indirect affected method IDs; and direct impact paths. Application `CALLS` edges come from ASM invocation instructions whose target class, method, and descriptor were found in the analyzed application's compiled classes. Component 7 traverses those edges backwards from methods that directly use changed APIs; indirect callers are potentially affected, not definitely broken.

## Console reporting

The console separates dependency-change records, library API-diff records, application API-reference records, unique affected application methods, call-graph edges, and selected test cases. API-impact previews count deduplicated API/member-to-application-use records; they are not method counts. Directly affected methods, indirect callers, and their final union are reported as distinct method sets. Class-file totals distinguish discovered candidates, successful analyses, failures, and duplicate paths intentionally skipped; incomplete-module and diagnostic counts are reported separately.

Long result lists are shown as bounded previews. The default preview limit can be changed with `-Danalysis.console.preview.limit=<limit>`. Detailed graph and regression-selection data remain in the JSON outputs. POM, module, class-file, and test-selection failures are surfaced as diagnostics; an individual unreadable historical POM does not suppress dependency changes successfully parsed from other POMs in that commit.

You can pass the target path directly as well:

```powershell
java -cp target/classes smartdeptest.Main C:\path\to\target-application
```

Maven stages the runtime JARs under `target/dependency`; `Main` loads them while keeping the project artifact's classes separate.

Compile the target project's relevant modules first. SmartDepTest reads the effective Maven build output directory, including custom compiler-plugin output directories; missing compiled classes are reported instead of treated as no impact. From the target project root, run `mvn compile` before starting SmartDepTest.

For both dependency versions, the analyzer invokes Maven from the target project's directory and passes the POM that declared the change. Maven applies that project's repositories, parent configuration, user settings, mirrors, and local cache. Maven copies resolved JARs to a temporary analysis directory; SmartDepTest does not guess the local-repository path or alter the target POM. For application analysis, it asks Maven for each module's compile classpath and scans each relevant compiled class once. Components 8–10 inspect compiled test methods across the Maven modules and trace bytecode invocations through the current application's `CALLS` graph. The test discovery supports JUnit 3 `TestCase` methods (including inherited tests), JUnit 4/5, and TestNG. Test output paths use the module's configured Maven test-output directory when present. If test bytecode is missing or older than test sources, SmartDepTest runs Maven `test-compile` with tests skipped (and Checkstyle skipped); it does not run tests or change test sources. The graph-aware path does not consume JaCoCo `.exec` or XML data: its associations are static call-path evidence, not proof that a test was executed or that a conditional runtime path was taken. Aggregate JaCoCo XML alone cannot attribute methods to individual tests. The enriched report is written to `target/smartdeptest-coverage-selection.json`.

For a large repository, increase the Git command timeout from its 120-second default:

```powershell
mvn compile
java -Dsmartdeptest.git.timeout.seconds=300 -cp target/classes smartdeptest.Main C:\path\to\target-application
```

## VS Code impact-report extension

The `extension` directory contains a VS Code extension that launches the same
Java analysis engine. It does not substitute mock data or run a second analysis
implementation. The original interactive CLI remains available as described
above.

The structured engine entry point can also be invoked without VS Code:

```powershell
mvn package
java -cp target/classes smartdeptest.Main --json C:\temp\smartdeptest-impact.json C:\path\to\maven-git-project
```

The JSON report uses schema version 1 and includes the dependency/API graph,
affected methods, selected tests with available source/call-path evidence,
test-selection diagnostics and an explicitly absent baseline when no baseline
data was supplied.

### Build and package

Requirements:

- JDK 21 or newer and Maven (already required by the analyzer).
- Node.js 20 or newer with npm.
- VS Code 1.85 or newer.

From the repository root, first build the Java engine and runtime libraries,
then compile and package the extension:

```powershell
mvn package
Set-Location extension
npm install
npm test
npm run compile
npm run package
```

The VS Code prepublish hook copies the built application JAR and runtime
dependencies from `target` into `extension\engine` before creating the
`.vsix`. Install the resulting `smartdeptest-impact-1.0.0.vsix` from VS Code's
**Extensions: Install from VSIX...** command. Do not commit the generated
`extension\engine` contents or `.vsix`; rebuild them from the Maven outputs.

### Run and debug

Open a Maven Git repository in VS Code and run **SmartDepTest: Analyze
Project** from the Command Palette, the SmartDepTest activity bar, or the
Explorer context menu on a Maven project folder or `pom.xml`. For a multi-root
workspace, pick the Maven project to analyze. The extension requires a root `pom.xml`, invokes
the packaged Java engine with a structured JSON-output option, reports Maven,
Git, API-comparison and test-mapping failures, and opens the impact report in a
Webview. Configure `smartdeptest.javaExecutable` if `java` is not on `PATH`, or
`smartdeptest.analysisTimeoutMinutes` for large projects. For extension
development, open `extension` in VS Code, run `npm install`, use **Run
Extension** (F5), and invoke the same command in the Extension Development
Host. The SmartDepTest activity-bar view summarizes dependencies, changed
APIs, affected application methods, selected tests, and warnings; the
**SmartDepTest** output channel captures engine output and the status bar
indicates analysis state. Before execution, the extension checks Git, Maven,
and JDK 21+ and reports actionable configuration and dependency-resolution
errors.

The Webview initially shows at most three selected tests; **Show all N tests**
expands the full deduplicated selection. Source links are emitted only when a
matching project source file was found. A missing line number opens the file
without a selection. **Download Report** saves a self-contained HTML report
with every selected test and escaped dynamic content.

The selection is static bytecode call-path evidence, not runtime coverage.
When the engine cannot retain a test-to-method mapping it explains that
limitation in the report. Source lines are best-effort Java declaration
locations; unknown locations remain unset. This repository does not implement
an RTS baseline or per-test runtime coverage, so comparison results are omitted
unless a future engine version supplies real baseline data. A missing baseline
is never treated as an executed comparison.

## Pipeline guides

- [Architecture guide](docs/architecture/README.md): dependency detection, ASM API comparison, bytecode analysis, and report responsibilities.
- [Detailed design guide](docs/detailed-design/README.md): Git detection, API comparison, bytecode usage, and impact rules.

## Current limits

The Component 1 parser resolves properties declared in the same POM and versions declared in that POM's dependency management. It compares declared POM entries; it does not resolve transitive dependency updates or build Maven's complete effective POM for dependency detection. ASM analysis supports Maven modules whose effective compiler output directories can be resolved and whose compiled bytecode is present. In Components 8–10, `NONE FOUND` means no statically traceable test call path was found; the report explicitly notes that this is not proof of runtime non-coverage. Reflection, dynamically loaded code, virtual dispatch not represented in the application call graph, and paths outside the compiled application call graph cannot be attributed by this static analysis.

For runtime verification, configure the target project's Surefire/Failsafe `argLine` to preserve JaCoCo's generated `@{argLine}` agent option, then run each discovered test method separately with its own JaCoCo destination file. For example:

```powershell
mvn -pl path/to/module org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent `
  -Dtest=example.StoreTest#testOpen -Djacoco.destFile=target/jacoco-per-test/StoreTest-testOpen.exec test
```

Map a method only from the execution data produced for that isolated test invocation; aggregate JaCoCo XML is not per-test evidence. SmartDepTest currently reports static call-path evidence and does not ingest those per-test `.exec` files.

The history search has no fixed commit-count limit. A shallow Git clone only contains part of the history; if no change is found in the available portion, the program asks you to fetch more history.
