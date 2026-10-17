# SmartDepTest VS Code Extension

This extension wraps the existing Java-based SmartDepTest analysis engine without modifying it. It adds a dedicated Activity Bar view for starting analysis, viewing status, and reviewing the current session history.

## Setup

1. Ensure Java 21 or newer is installed and available on `PATH`.
2. Ensure the original SmartDepTest project exists in a sibling directory or at the configured location.
3. Install dependencies and compile the extension:

```powershell
cd "C:\Users\HP\OneDrive\Desktop\Documents\Mega Project\SmartDepTest-VSCode"
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
npm install
npm run compile
```

## Configuration

Use the VS Code settings under `SmartDepTest`:

- `smartDepTest.componentPath`: path to the Java SmartDepTest project root.
- `smartDepTest.projectPath`: optional target Maven Git project to analyze.
- `smartDepTest.javaPath`: Java executable path if needed.
- `smartDepTest.timeoutMs`: execution timeout in milliseconds.

The default component path points to the sibling `../SmartDepTest` folder relative to this extension project.

## Usage

1. Click the SmartDepTest icon in the Activity Bar.
2. Use the visible Start Analysis button.
3. Pick the target Maven Git project if prompted.
4. Review the output and previous session history in the sidebar.
5. Select any run to view its report, then use **Save Report** to choose a `.txt` destination. If the selected run produced JSON artifacts, use the separate JSON export buttons shown below the report.

## Notes

- This extension tracks all runs in memory for the current VS Code session.
- Text reports and JSON exports are saved only when you choose an export destination. JSON files are snapshotted per run in memory so later runs cannot replace an older run's exports.
- It does not persist run history across restarts.
- The original SmartDepTest component remains unchanged.

## Packaging

```powershell
npm run package
```

This creates a `.vsix` from the extension project.
