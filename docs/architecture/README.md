# Architecture Guide: Components 1-7

This guide is a map of the code. Start here if you want to know which file owns a piece of the behavior.

## The whole program in one picture

```text
You enter a project folder
          |
          v
Main.java: checks input and starts detection
          |
          v
DependencyChangeDetector.java: coordinates the work
          |
          +--> GitRepositoryAnalyzer.java: finds POMs and reads Git history
          |
          +--> PomParser.java: turns each POM into dependency data
          |
          +--> DependencyComparator.java: compares old and new dependency data
          |
          v
DependencyChangeResult.java: stores the final report data
          |
          v
Main.java: prints the existing Component 1 report
          |
          v
APIChangeAnalyzer.java: resolves old/new JARs
          |
          v
JApiCmpApiComparator.java: compares dependency APIs and normalizes JVM descriptors
          |
          v
APIChangeResult.java
          |
          v
APIUsageAnalyzer.java: delegates to BytecodeAPIUsageAnalyzer
          |
          +--> ApplicationModuleScanner.java: locates Maven modules and effective output directories
          +--> ASM visitors: index method, field, and class references once per class
          |
          v
APIUsageResult.java: evidence and potential-impact classification
          |
          v
DependencyGraphBuilder.java: nodes, evidence-backed edges, and direct impact paths
          |
          v
DependencyGraphJsonExporter.java: target/smartdeptest-impact-graph.json
```

Component 2 and Component 3 consume `DependencyChangeResult`; they do not repeat Git history or POM dependency detection. Their report is appended after the existing Component 1 output.

## First, understand the commit comparison

The program does **not** assume that the latest two commits contain the dependency change.

It searches the current branch's available first-parent history for commits that touched a discovered POM. There is no fixed commit-count limit. For each candidate, it compares that commit with its immediate first parent:

```text
parent commit POM  ->  candidate commit POM  ->  compare dependencies
```

If the POM changed but the dependency data did not, it tries the next older candidate. It stops at the newest candidate where the dependency data changed, or reports that none was found after reaching the available history.

## How projects with many POM files are handled

A Maven project can have one POM or hundreds. The detector supports multiple POM paths; it does not assume there is only a root `pom.xml`.

Here is what happens when there are many:

1. **Discover every current POM path.** `GitRepositoryAnalyzer.discoverPomFiles()` recursively walks the project folder and collects files named `pom.xml`. It excludes `.git`, `target`, and `node_modules`. For example, the result may contain `pom.xml`, `service-a/pom.xml`, and `service-b/pom.xml`.
2. **Search Git history using all discovered paths.** `historyCandidates(pomFiles)` uses one path-limited `git log` command to collect each candidate commit, its first parent, and the POM paths changed by that commit. There is no `--max-count` limit; Git returns candidates newest first until it reaches the available first-parent history.
3. **Narrow down for each candidate commit.** `changedPomFiles(parent, commit, pomFiles)` asks which of the discovered POMs actually changed in that commit. POM files untouched by that commit are not parsed for that comparison.
4. **Read and compare changed POMs one at a time.** For each changed path, the detector reads the parent and candidate snapshots, parses each snapshot, and adds its changes to the candidate's result list. It then moves on to the next changed POM.
5. **Return one combined report.** If any changed POM contains a dependency change, the detector returns the candidate commit and all detected changes from its changed POM files.

So the short answer is: **all current POM paths are discovered and used to search Git, but the program only parses POMs changed by each candidate commit.** It does not parse every POM for every commit.

### Example

Suppose the project has 200 POM files, but a candidate commit changed only `service-a/pom.xml` and `service-b/pom.xml`. The detector reads and compares those two POMs for that candidate, not all 200. If the candidate has no dependency change, it checks the next older candidate commit.

### Scale and coverage limits

- The full list of discovered POM paths is passed to Git in a single command. Hundreds of paths are expected to work in ordinary projects, but an exceptionally large path list could run into operating-system command-line length limits. The current implementation does not batch paths.
- A large repository can take longer because the detector walks the project tree and checks POM-changing commits newest first. It does not parse POMs for commits that did not touch a discovered POM, but it may parse the changed POMs for several unrelated POM commits before finding the dependency change. The Git timeout is configurable; there is no parallel parsing or benchmark-based limit today.
- A shallow clone only exposes its downloaded history. If the search reaches that boundary without a change, the detector reports that more history must be fetched before it can rule out an older dependency change.
- POM paths come from the current checkout. A POM deleted from the current checkout is not discovered, so this version may not report its deletion from history.
- POMs are parsed independently. The parser does not calculate a multi-module Maven effective POM or apply properties inherited from a parent POM.

## What each source file does

### Program entry and workflow

**`src/main/java/smartdeptest/Main.java`**

- `main()` asks for the Maven project path, checks that it exists, and calls the detector.
- `printReport()` displays the selected commit, changed POMs, dependency changes, and totals.

**`src/main/java/smartdeptest/dependency/DependencyChangeDetector.java`**

- `detect()` is the coordinator. It asks Git for POM files and candidate commits, compares each candidate with its parent, and returns the first dependency-changing result.

### Git and POM reading

**`src/main/java/smartdeptest/dependency/GitRepositoryAnalyzer.java`**

- `verifyRepository()` checks that the folder is a Git repository.
- `discoverPomFiles()` finds `pom.xml` files in the checked-out project.
- `historyCandidates()` gets candidate IDs, first-parent IDs, and changed POM paths in one Git history command, with no fixed commit-count limit.
- `historyCommits()`, `firstParent()`, and `changedPomFiles()` are lower-level helpers for querying those values individually.
- `readPom()` reads a POM's exact contents from a Git commit.
- `runGit()` and `runGitAllowFailure()` run Git commands and enforce the timeout.

**`src/main/java/smartdeptest/dependency/PomParser.java`**

- `parse()` converts one POM into a map of dependencies.
- `readDependency()` extracts one dependency's fields.
- `readProperties()` and `resolve()` handle properties declared in that same POM.
- `parseXml()` configures secure XML parsing.
- `text()` and `children()` read XML elements.

### Comparing and storing results

**`src/main/java/smartdeptest/dependency/DependencyComparator.java`**

- `compare()` finds dependencies that were added or removed.
- `addMetadataChanges()` checks version, scope, type, classifier, and optional fields.

**`src/main/java/smartdeptest/dependency/Dependency.java`**

Stores one parsed dependency. `getKey()` distinguishes a direct dependency from one in `dependencyManagement`.

**`src/main/java/smartdeptest/dependency/DependencyChange.java`**

Stores one detected change, including its old/new values and POM/commit information.

**`src/main/java/smartdeptest/dependency/DependencyChangeResult.java`**

Stores the full result: project path, selected commit and parent, commit message, changed POM paths, and all changes.

### API comparison and application usage

**`src/main/java/smartdeptest/analysis/APIChangeAnalyzer.java`**

- `analyze()` groups Component 1 changes by artifact/version/declaration and obtains old/new JARs.
- `MavenArtifactResolver` invokes Maven from the target project directory with the POM path containing the changed dependency. Maven applies target-POM repositories, parent inheritance, user settings, mirrors, and its configured local repository; resolved JARs are copied into temporary analysis storage.
- `JApiCmpApiComparator` compares only the dependency JARs. It emits changed classes, methods, constructors, and fields as normalized `ApiChange` values with owner internal names and old/new JVM descriptors.
- Results are `ANALYZED` or `UNAVAILABLE`; failure to resolve an artifact is not reported as “no API changes.”

**`src/main/java/smartdeptest/analysis/APIUsageAnalyzer.java`**

- `analyze()` receives `APIChangeResult` plus the application project path and delegates bytecode analysis.
- `ApplicationModuleScanner` discovers Maven modules and asks `MavenModuleClasspathResolver` for each module's effective Maven build/compiler output directories. The standard `target/classes` directory is a fallback when Maven model resolution fails and that directory exists.
- `MavenModuleClasspathResolver` obtains each module's compile classpath through Maven. It first selects the module from the target root reactor with `-pl <module> -am`, allowing sibling SNAPSHOT projects to resolve in-reactor; it falls back to the standalone module POM if the root is not a usable reactor. The classpath is written to a temporary file and deleted afterward.
- `BytecodeAPIUsageAnalyzer` builds hash-based keys from changed API owners and descriptors, then scans each relevant application class once with ASM. It checks method instructions, field instructions, type references, descriptors, and method handles.
- A module's resolved compiled output directories are scanned once. A module is checked for a changed dependency only when its compile classpath contains the updated dependency. Missing compiled classes or malformed bytecode produce `ANALYSIS_UNAVAILABLE`, not a no-impact result.
- `APIUsageResult` reports impact classification and application class/method descriptors plus instruction kinds for matches.

### Dependency/impact graph and affected code

**`src/main/java/smartdeptest/graph/DependencyGraphBuilder.java`** consumes `APIChangeResult` and `APIUsageResult`; it does not scan Maven files or bytecode. It creates dependency nodes from the JApiCmp result metadata, API nodes from `ApiChange`, and application class/method nodes only for `used` ASM findings.

- Dependency-to-API `PROVIDES` edges come from JApiCmp changes.
- Class-to-API `USES` and method-to-API `USES` edges come from ASM usage locations. Application invocation instructions create method-to-method `CALLS` edges only when the target method is present in the discovered application bytecode. Class-to-method `CONTAINS` edges preserve the owner relationship.
- Direct `ImpactPath` values preserve the dependency, API, application class, and (when the finding identifies one) application method IDs and instruction type.
- No application-to-application call graph is currently produced, so propagation intentionally stops at directly using code. Transitive dependency status is `NOT_ANALYZED`; the existing detector compares declared POM entries and dependency-management declarations, not Maven's resolved transitive graph.
- `DependencyGraphJsonExporter` writes `target/smartdeptest-impact-graph.json` under the analyzed project. The JSON contains `nodes`, `edges`, `affectedNodes`, and `impactPaths`.

**Analysis result models**

- `analysis/ApiChange.java`: one class/member addition, removal, or modification, with JVM descriptors, old/new signatures, and an incompatibility flag.
- `analysis/DependencyApiResult.java` and `analysis/APIChangeResult.java`: API differences grouped by dependency and version.
- `analysis/APIUsageResult.java`: per-dependency classification and per-API bytecode locations.
- `analysis/ApplicationModule.java`: discovered module/POM/compiled-output data passed to ASM analysis.

## Where to make a change

| If you want to change... | Start in... |
| --- | --- |
| Which commits or files are inspected | `GitRepositoryAnalyzer` and `DependencyChangeDetector` |
| Which Maven XML values are read | `PomParser` |
| What counts as a dependency change | `DependencyComparator` and `DependencyChange.Type` |
| What the command prints | `Main.printReport()` |
| What information a result stores | `Dependency`, `DependencyChange`, or `DependencyChangeResult` |
| How dependency JAR APIs are compared | `analysis/APIChangeAnalyzer` and `analysis/JApiCmpApiComparator` |
| How actual application usage is found | `analysis/BytecodeAPIUsageAnalyzer` and `analysis/MavenModuleClasspathResolver` |
| How affected classes/methods are represented and exported | `graph/DependencyGraphBuilder` and `graph/DependencyGraphJsonExporter` |

## Tests

- `src/test/java/smartdeptest/dependency/Component1Test.java` checks parsing and comparison rules.
- `src/test/java/smartdeptest/dependency/DependencyChangeDetectorTest.java` creates small temporary Git repositories and checks commit selection.
- `src/test/java/smartdeptest/analysis/AnalysisPipelineTest.java` creates local synthetic JARs and compiled application modules to check descriptor-sensitive bytecode usage and impact classifications without network access.
- `src/test/java/smartdeptest/analysis/MavenModuleClasspathResolverTest.java` verifies root-reactor module selection, `-am`, and standalone-POM fallback.

Run both groups with `mvn test`.

## What this component does not do

The API-impact pipeline compares dependency JARs and inspects compiled application bytecode. It does not execute the application, prove runtime behavior, build a whole-program call graph, inspect test bytecode, analyze coverage, or select tests. Component 1 compares declared POM entries and does not resolve transitive dependency updates.