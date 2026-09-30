# Detailed Design: Dependency-to-Impact Evidence

This guide follows the evidence chain through Components 1–3. Component 1 identifies the committed dependency change; Components 2 and 3 inspect API declarations and source usage. The final label is static-analysis evidence, not a prediction that the application will crash.

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
	-> resolve changed API references in application source
	-> report potential impact only when incompatible changed APIs are used
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

`APIChangeAnalyzer.analyze(DependencyChangeResult)` consumes the Component 1 result directly. It groups repeated change records for the same dependency/version pair so one dependency is analyzed once.

`MavenArtifactResolver.resolveJar()` checks the standard local repository first:

```text
~/.m2/repository/<group path>/<artifact>/<version>/<artifact>-<version>[-classifier].jar
```

If a required JAR is missing, it invokes Maven's `dependency:get` goal for that exact artifact with transitive resolution disabled. No application POM is edited. If coordinates are unresolved, the JAR is missing after resolution, or the artifact is not a JAR dependency, the API result is `UNAVAILABLE`; it is not treated as an empty API or “no impact.”

When the old and new versions are identical, JAR comparison is skipped. Additions/removals without both versions are marked unavailable because Component 1 has no old/new pair to compare.

## Step 9: Compare public and protected API declarations

`ApiSurfaceReader` uses the JDK compiler model to inspect class files in each JAR. The key includes class identity and erased method parameter types; the displayed signature retains generic source types. This lets a changed parameter/return/throws/modifier declaration be reported as `MODIFIED`, while a changed parameter list is paired as a method signature change when it is unambiguous.

It records visible classes, constructors, methods, and fields, including class inheritance/interface declarations and field constant values. Ordinary added declarations are API changes but are not marked incompatible by themselves. A newly added abstract method is treated as a potentially incompatible contract change; source is considered affected only when an application class implements or extends that interface/abstract class. Removals and changed declarations are conservatively marked potentially incompatible. Private/package-private implementation members are excluded.

Each dependency result is `ANALYZED` with zero or more `ApiChange` entries, or `UNAVAILABLE` with a reason. The result retains dependency coordinates and old/new versions for traceability.

## Step 10: Discover modules and resolve real source usage

`ApplicationModuleScanner` walks the project and discovers standard `src/main/java` folders, including module folders. It skips `.git`, `target`, and `node_modules`, then associates each source root with its nearest `pom.xml`.

`MavenModuleClasspathResolver` asks Maven's `dependency:build-classpath` goal for each module's compile classpath. It writes the output to a temporary file and removes that file. Sibling module `target/classes` directories and source roots are also provided to the compiler where available. Maven may resolve the project's compile dependencies if they are not cached, which is needed for semantic type attribution.

`APIUsageAnalyzer` analyzes each module once with a JDK `JavacTask`, rather than rescanning all source for every changed API. It compiles for attribution only; it does not execute application code. Its tree scanner skips imports as evidence and records resolved calls, constructor calls, method references, field uses, and type references. Locations include project-relative source path, class, method, and line.

For each incompatible API change, the scanner matches the old API symbol. Ordinary added APIs are retained in the API report but are not impact triggers. For a new abstract contract method, it instead checks whether an application class implements or extends the declaring type.

## Step 11: Interpret the impact label

| Classification | Meaning |
| --- | --- |
| `POTENTIAL_IMPACT` | At least one potentially incompatible changed API was semantically resolved at an application source location. This does not prove a runtime failure. |
| `NO_IDENTIFIED_IMPACT` | API comparison completed and no incompatible changed API was found in the indexed application source. |
| `ANALYSIS_UNAVAILABLE` | An artifact, source root, Maven classpath, or required symbol could not be analyzed completely; a negative result would not be trustworthy. |

An added method can appear as `METHOD_ADDED` while impact remains `NO_IDENTIFIED_IMPACT`. If application source uses a new-version-only API that cannot resolve against the old JAR, compiler errors make the negative result `ANALYSIS_UNAVAILABLE`. If a removed old API is still resolved as used while other source errors exist, the positive evidence remains `POTENTIAL_IMPACT` and the report includes an analysis note.

## Evidence and limitations

- Component 1's selected parent/candidate versions remain the sole source of dependency-change facts; Components 2–3 do not inspect commit messages or repeat Git detection.
- JAR analysis uses public/protected declarations, not implementation bytecode. It does not establish behavioral compatibility, runtime linkage, reflection strings, dynamically loaded classes, or service configuration changes.
- Source analysis covers Java under standard Maven `src/main/java`; test sources, generated sources, Kotlin, and nonstandard source roots are not included. A module is checked for a changed dependency only if that artifact is on the module's Maven compile classpath.
- Maven is asked for each module's compile classpath. Uncached project compile dependencies or Maven plugins may be downloaded by Maven. The application is not run, and its POM is not changed.
- Java semantic errors are retained as incomplete-analysis evidence. A module with unresolved symbols prevents a “no impact” conclusion when an incompatible API might be used.
- API analysis supports JAR artifact types. Parent inheritance, profiles, imported BOMs, shaded/relocated classes, and all Maven classifier/type edge cases are not fully modeled.
- A method parameter signature change is paired as `METHOD_MODIFIED` only if one removed and one added method with that owner/name can be paired unambiguously; otherwise they remain separate removed/added API records.
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