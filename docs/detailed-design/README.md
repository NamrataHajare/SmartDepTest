# Detailed Design: Dependency-to-Impact Evidence

This guide follows the evidence chain from committed dependency detection through JApiCmp comparison and ASM bytecode usage analysis. The final label is static-analysis evidence, not a prediction that the application will crash.

## The process at a glance

```text
Project folder
	-> find pom.xml files
	-> search available first-parent history for commits that changed a POM
	-> compare each commit with its first parent
	-> read both committed POM versions from Git
	-> parse and compare dependencies
	-> report the newest commit with a dependency change
	-> compare the dependency's old/new JAR APIs
	-> normalize changed API owners and JVM descriptors
	-> scan relevant target/classes bytecode once with ASM
	-> report application classes/methods that reference changed APIs
```

## Step 1: Find the project POM files

`Main.main()` asks for a project path and checks that it is a directory. Then `DependencyChangeDetector.detect()` calls `GitRepositoryAnalyzer.discoverPomFiles()`.

That method walks the checked-out folder and finds files named `pom.xml`. It skips `.git`, `target`, and `node_modules`, then stores project-relative paths such as:

```text
pom.xml
module-a/pom.xml
```

This scan finds the paths only. It does **not** read the current working-copy contents for comparison.

## Step 2: Choose commits to inspect

`GitRepositoryAnalyzer.historyCandidates()` asks Git for commits on the current branch's first-parent history that changed one of those POM paths. One Git command returns the candidate IDs, their first-parent IDs, and their changed POM paths. There is no fixed commit-count limit. The equivalent history query is:

```text
git log --first-parent --no-renames --format=%H -- <POM paths>
```

The candidates are newest first. The first parent is included in the history result, so the detector does not need a separate parent lookup for each candidate. A root commit has no parent and is skipped. A merge commit is compared with its **first parent only**. The search continues until a dependency-changing commit is found or Git reaches the oldest commit available in the repository.

So “two commits” means the candidate commit and its parent, not necessarily the two newest commits in the whole repository.

## Step 3: Find the POMs changed by that commit

`changedPomFiles(parent, commit, pomFiles)` asks Git which discovered POM paths differ between the parent and candidate. The detector only parses those changed POMs for this comparison.

## Step 4: Read the before-and-after file contents

For each changed POM, `readPom()` runs the equivalent of:

```text
git cat-file blob <commit>:<path-to-pom.xml>
```

It reads once from the parent and once from the candidate. These are **committed snapshots**, so uncommitted edits in the working folder do not affect the result.

If a Git blob cannot be read, the parser receives invalid/empty XML and detection stops with an error naming the POM and commit.

## Step 5: Turn XML into dependency data

`PomParser.parse(xml, pomPath)` reads the POM with the JDK's secure DOM XML parser. It creates a map of `Dependency` objects.

For each dependency, it reads:

| POM field | Value used when missing |
| --- | --- |
| `groupId`, `artifactId` | Required; entry is skipped if either is blank |
| `version` | `NOT_SPECIFIED` |
| `scope` | `compile` |
| `type` | `jar` |
| `classifier` | Empty string |
| `optional` | `false` |

The parser reads `dependencyManagement` first, then direct `<dependencies>`. If a direct dependency has no version, the parser uses a matching version from the same POM's dependency management, if available. It keeps managed and direct declarations as separate entries.

### Property values

`readProperties()` collects properties declared in that POM. `resolve()` replaces expressions such as `${library.version}` using those local values, repeating up to 20 times to allow one property to refer to another.

An unknown expression stays as written. Parent POM properties, profiles, imported BOMs, and Maven's full effective-POM rules are not resolved by this parser.

## Step 6: Compare the old and new dependency maps

Each `Dependency` has a map key that includes whether it is direct or managed:

```text
direct:org.example:sample-library
managed:org.example:sample-library
```

This keeps declarations in different POM sections separate. `DependencyComparator.compare()` compares the keys and fields:

| What changed? | Reported type |
| --- | --- |
| Dependency appears | `ADDED` |
| Dependency disappears | `REMOVED` |
| Version changes | `UPDATED` |
| Scope changes | `SCOPE_CHANGED` |
| Type changes | `TYPE_CHANGED` |
| Classifier changes | `CLASSIFIER_CHANGED` |
| Optional flag changes | `OPTIONAL_CHANGED` |

One dependency can produce multiple change entries if multiple fields changed. If `groupId` or `artifactId` changes, the result is a removal of the old coordinates and an addition of the new coordinates.

## Step 7: Select and print the result

`DependencyChangeDetector.detect()` checks candidates from newest to oldest. It returns as soon as one candidate produces at least one dependency change. It does not return every historical dependency change.

`DependencyChangeResult` carries the selected commit, its parent, commit message, changed POM paths, and change list. `Main.printReport()` formats that data for the terminal.

## Step 8: Resolve the changed dependency artifacts

`APIChangeAnalyzer.analyze(DependencyChangeResult)` consumes the Component 1 result directly. It groups repeated change records by dependency/version and declaration metadata so distinct direct and dependency-management entries are not collapsed.

`MavenArtifactResolver.resolveJar()` invokes Maven from the target project root and passes the POM path that declared the changed dependency with `-f`. It uses Maven's `dependency:copy` goal to resolve the exact old or new JAR and copy it to a temporary analysis directory. It does not assume a local-repository path or repository URL.

Because resolution runs against the target project's POM, Maven uses repositories declared in that POM, repositories inherited from resolvable parents, the user's `settings.xml`, configured mirrors, and Maven's configured local repository/cache. Maven itself decides which configured source supplies the artifact. Normal Maven runs do not force remote updates; if Maven explicitly reports a cached missing-artifact result, SmartDepTest retries that command once with `-U`. The target project's POM and source files are not changed. If coordinates are unresolved, Maven cannot resolve an artifact, or the artifact is not a JAR dependency, the API result is `UNAVAILABLE`; it is not treated as an empty API or “no impact.” The Maven error is retained in the analysis reason.

When the old and new versions are identical, JAR comparison is skipped. Additions/removals without both versions are marked unavailable because Component 1 has no old/new pair to compare.

## Step 9: Compare dependency APIs with JApiCmp

`JApiCmpApiComparator` compares only the old and new dependency JARs. It includes public and protected classes, methods, constructors, and fields and maps JApiCmp statuses to normalized `ApiChange` values. Class owners use JVM internal names such as `org/example/Service`. Methods and constructors retain old and new JVM descriptors, including return types; fields retain their old and new descriptors. An unambiguous same-owner/name method remove/add pair is represented as a modified signature while preserving both descriptors. Removed APIs retain their old descriptor for matching application bytecode that still links to them.

Each dependency result is `ANALYZED` with zero or more changes, or `UNAVAILABLE` with a reason. The result retains dependency coordinates, versions, scope/type/classifier, POM origin, and whether the declaration came from `dependencyManagement`.

## Step 10: Scan compiled application bytecode

`ApplicationModuleScanner` discovers Maven modules from standard `src/main/java` roots or existing `target/classes` output and associates each with its nearest `pom.xml`. The application must be compiled before analysis; missing bytecode for a relevant module is `ANALYSIS_UNAVAILABLE`, not “no impact.”

`MavenModuleClasspathResolver` asks Maven's `dependency:build-classpath` goal for each module's compile classpath. For a multi-module project, it first invokes Maven from the project root with `-f <root pom> -pl <module path> -am`; this includes required sibling SNAPSHOT projects in the reactor. If the root POM is not a usable reactor, it retries with the module POM directly. The classpath output goes to a temporary file and is removed.

`BytecodeAPIUsageAnalyzer` builds hash-based keys from owner/name/descriptor (or owner/name/field descriptor), then walks each relevant module's class files once. It reads method invocation instructions, field instructions, class/type references, descriptor types, and method handles. When a reference owner inherits a changed method or field, it resolves cached class headers through the superclass/interface chain; a nearer declaration stops the search so an override is not attributed to the changed ancestor. Each match records the application internal class name, method name and descriptor, and instruction kind. A changed dependency is associated with a module only when it appears on that module's Maven compile classpath.

Changed APIs are reported independently from their usages. An added or modified API with no bytecode match has no identified application impact; a matched changed API is reported as a potential impact. Abstract contract additions also match classes that implement or extend the declaring type.

## Step 11: Interpret the impact label

| Classification | Meaning |
| --- | --- |
| `POTENTIAL_IMPACT` | ASM found at least one application bytecode reference to a changed API. This does not prove a runtime failure. |
| `NO_IDENTIFIED_IMPACT` | API comparison completed and no changed API reference was found in scanned application bytecode. |
| `ANALYSIS_UNAVAILABLE` | An artifact, relevant `target/classes`, Maven classpath, or class file could not be analyzed completely; a negative result would not be trustworthy. |

The report lists changed APIs even when none are used. For a modified method, references matching either old or new descriptor are retained; removed methods and fields match their old descriptors. Impact is bytecode evidence only and does not establish a runtime failure.

## Evidence and limitations

- Component 1's selected parent/candidate versions remain the sole source of dependency-change facts; Components 2–3 do not inspect commit messages or repeat Git detection.
- JAR analysis uses public/protected declarations, not implementation bytecode. It does not establish behavioral compatibility, runtime linkage, reflection strings, dynamically loaded classes, or service configuration changes.
- Bytecode analysis covers compiled classes under Maven `target/classes`; test output, reflection strings, dynamically loaded classes, and nonstandard output directories are not included. A module is checked for a changed dependency only if that artifact is on the module's Maven compile classpath.
- Maven is asked for each module's compile classpath. Uncached project compile dependencies or Maven plugins may be downloaded by Maven. The application is not run, and its POM is not changed.
- The project must be compiled before analysis. Missing or malformed class files prevent a “no impact” conclusion for the affected module.
- Component 1 compares declared POM entries and dependency-management entries; it does not resolve transitive dependency changes. No direct/transitive status is inferred from bytecode.
- API analysis supports JAR artifact types. Parent inheritance, profiles, imported BOMs, shaded/relocated classes, and all Maven classifier/type edge cases are not fully modeled.
- A method signature change is paired as `METHOD_MODIFIED` only if one removed and one added method with that owner/name can be paired unambiguously; otherwise they remain separate removed/added API records.
- Impact means “potentially impacted,” not “will fail.” Runtime validation and test selection belong to later components.

## Current limits to remember

- There is no fixed limit on the number of POM-changing commits inspected. A long history with many unrelated POM edits can take longer because each candidate must be checked to avoid missing the newest actual dependency change.
- POM paths are discovered from the current checkout, so a POM deleted from the checkout is not discovered.
- Only committed POM content is compared; working-copy edits are ignored.
- Merge commits are compared with their first parent only.
- The parser handles local properties and local dependency management, not a complete Maven effective POM.
- Git must be installed and available on `PATH`. The default command timeout is 120 seconds; change it with `-Dsmartdeptest.git.timeout.seconds=<seconds>`.
- In a shallow clone, only downloaded history is available. If no dependency change is found before the shallow boundary, the detector asks for more history instead of treating the partial history as complete.

For example, adding parent-POM inheritance is more than changing `resolve()`: the detector would need to locate the parent POM and define how its properties and managed versions apply to the child.

## How to extend it safely

For a new parsing or comparison rule:

1. Add a focused old-POM/new-POM test in `Component1Test.java`.
2. Update `PomParser`, `DependencyComparator`, or `DependencyChange.Type`, as appropriate.
3. Update `Main.printReport()` if the new change needs different output.
4. Run `mvn test`.

For changes to Git history selection or snapshot reading, add an end-to-end test in `DependencyChangeDetectorTest.java` using a temporary Git repository.