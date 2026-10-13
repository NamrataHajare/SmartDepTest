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

Open a Maven Git project, then run **SmartDepTest: Analyze Dependency Impact**
from the Command Palette. For multi-root workspaces, select the Maven project.
Use **Run Extension** (F5) from this folder to debug in an Extension
Development Host.

The command runs the packaged Java engine; Java 21+ must be available as `java`
or configured with `smartdeptest.javaExecutable`. Change
`smartdeptest.analysisTimeoutMinutes` for larger projects.

The Webview shows the first three unique selected tests and expands the full
list with **Show all N tests**. **Download Report** saves a standalone HTML
file containing all selected tests. Source links are provided only when a
source file exists; an unavailable line opens the file without a selection.

Test associations and classifications are static call-graph evidence, not
runtime proof of coverage. Source locations are best effort. No RTS baseline
is included unless real baseline data is supplied by the analysis engine.
