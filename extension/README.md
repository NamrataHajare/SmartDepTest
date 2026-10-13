# SmartDepTest Impact Analysis

Run the existing SmartDepTest Java analyzer from VS Code and inspect dependency
API impact, affected application methods, and selected regression tests.

## Build and install

Requirements: JDK 21+, Maven, Node.js 20+, npm, and VS Code 1.85+.

From the repository root:

```powershell
mvn package
Set-Location extension
npm install
npm test
npm run compile
npm run package
```

The `vscode:prepublish` hook stages the Maven JAR and its runtime dependencies
before packaging. Install `smartdeptest-impact-1.0.0.vsix` using VS Code's
**Extensions: Install from VSIX...** command. `engine`, `out`, `node_modules`,
and `.vsix` artifacts are generated and should not be committed.

## Use and debug

Open a Maven Git project, then run **SmartDepTest: Analyze Project** from the
Command Palette or right-click a project folder or `pom.xml` in Explorer. The
SmartDepTest activity-bar view shows run status and a navigable summary of
dependencies, API changes, affected methods, selected tests, and warnings. For
multi-root workspaces, choose the Maven project to analyze.
Use **Run Extension** (F5) from this folder to debug in an Extension
Development Host.

The **SmartDepTest** output channel records the engine's Maven/analysis output;
the status bar reports running, completed, failed, or cancelled state. Git,
Maven, and JDK 21+ are validated before launching analysis, with actionable
messages for missing configuration and dependency-resolution errors.

The command runs the packaged Java engine; Java 21+ must be available as `java`
or configured with `smartdeptest.javaExecutable`. Maven must be available as
`mvn`; if VS Code was opened before Maven was added to `PATH`, set
`smartdeptest.mavenExecutable` to the full path of `mvn.cmd` (for example,
`C:\tools\apache-maven\bin\mvn.cmd`). That executable is used both for
prerequisite validation and by the analyzer during dependency resolution.
Change `smartdeptest.analysisTimeoutMinutes` for larger projects.

The Webview shows the first three unique selected tests and expands the full
list with **Show all N tests**. **Download Report** saves a standalone HTML
file containing all selected tests. Source links are provided only when a
source file exists; an unavailable line opens the file without a selection.

To create a downloadable VS Code installer (`.vsix`), build the Java engine
from the repository root, then run `npm install` and `npm run package` from
`extension`. Install it with **Extensions: Install from VSIX...**.

Test associations and classifications are static call-graph evidence, not
runtime proof of coverage. Source locations are best effort. No RTS baseline
is included unless real baseline data is supplied by the analysis engine.
