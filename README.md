# SmartDepTest Component 1

Automatic Maven dependency change detection from Git history.

## Build and test

From PowerShell:

```powershell
cd "C:\Users\HP\OneDrive\Desktop\Documents\Mega Project\SmartDepTest\SmartDepTestComponent1"
mvn test
```

If Maven is not on `PATH`, use the Maven installation directly:

```powershell
& "C:\Program Files\apache-maven-3.9.16\bin\mvn.cmd" test
```

The test suite covers unchanged, added, removed, updated, multiple-update, scope, property-version, dependency-management, multi-module, invalid-POM, Git commit selection, and no-change cases.

## Run

```powershell
mvn package
java -cp target/classes smartdeptest.Main
```

For a large repository such as QuickFIX/J, Git commands use a 120-second timeout by default. Increase it when needed:

```powershell
java -Dsmartdeptest.git.timeout.seconds=300 -cp target/classes smartdeptest.Main
```

The program prompts only for the project directory. It verifies Git, discovers POM files recursively, checks the 10 most recent checked-out-branch commits affecting those POM files, and stops at the newest commit containing a structural dependency change. It then reads that commit's immediate parent, parses both POM versions, and prints a structured report. The bounded 10-commit window avoids scanning the complete repository history while still handling release/version commits immediately before a dependency update.

## Design notes

- `Dependency` is the handoff model for later components and preserves groupId, artifactId, resolved version, scope, type, classifier, optional, POM path, and dependency-management status.
- `DependencyChange` preserves old/new versions and metadata plus both commit IDs and the changed POM path.
- `DependencyChangeResult` is the complete Component 1 output.
- XML is parsed with JDK DOM APIs and external entities/DOCTYPEs are disabled.
- Local `${property}` values and local dependency-management versions are resolved. Maven parent inheritance, profiles, BOM imports, and complete Maven interpolation are intentionally outside this first component.
- No API analysis, application graph, impact analysis, test mapping, or regression selection is included.
