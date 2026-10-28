import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { getSmartDepTestSettings, resolveConfiguredPath } from './configuration/settings';
import { ensureExportExtension, getExportContent, getExportFilename, ReportExportKind } from './history/reportExports';
import { generateAndOpenHtmlImpactReport, openExistingHtmlImpactReport } from './history/htmlImpactReport';
import { SessionHistory } from './history/sessionHistory';
import { AnalysisRun, SidebarState } from './types/analysis';
import { SmartDepTestViewProvider } from './views/smartDepTestViewProvider';
import { cancelRunningProcess, executeSmartDepTest, resolveProjectRoot, validateComponentInstallation, validateTargetMavenProject } from './wrapper/smartDepTestRunner';
import { navigateToSource } from './wrapper/sourceNavigation';

const history = new SessionHistory();
let activeRunId: string | undefined;
let currentSelectedRunId: string | undefined;
let activeProcess: { kill: (signal?: NodeJS.Signals | number) => boolean } | undefined;
let activeRunCancelled = false;
let currentStatusText = 'Ready';
let currentProjectPath = '';
let outputChannel: vscode.OutputChannel | undefined;

export function activate(context: vscode.ExtensionContext): void {
  outputChannel = vscode.window.createOutputChannel('SmartDepTest');
  context.subscriptions.push(outputChannel);

  const provider = new SmartDepTestViewProvider(
    context.extensionUri,
    () => getSidebarState(),
    {
      onStartAnalysis: async () => { await startAnalysis(() => provider.refresh()); },
      onCancelAnalysis: () => { cancelActiveRun(); },
      onSelectRun: (runId) => {
        currentSelectedRunId = runId || undefined;
        provider.refresh();
      },
      onExportReport: async (runId, kind) => { await saveReport(runId, kind); },
      onOpenHtmlReport: async (runId) => { await openHtmlReport(runId); },
      onOpenSourceLocation: async (runId, sourcePath, lineNumber) => {
        await openSourceLocation(runId, sourcePath, lineNumber);
      },
      onRequestState: () => provider.refresh(),
    },
  );

  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider('smartdeptest.sidebar', provider),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand('smartdeptest.startAnalysis', async () => {
      await startAnalysis(() => provider.refresh());
    }),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand('smartdeptest.openSidebar', async () => {
      await vscode.commands.executeCommand('workbench.view.extension.smartdeptest');
    }),
  );

  provider.refresh();
}

export function deactivate(): void {
  if (activeProcess) {
    cancelRunningProcess(activeProcess);
  }
}

function getSidebarState(): SidebarState {
  const runs = history.getAll();
  const selected = currentSelectedRunId && runs.some((run) => run.id === currentSelectedRunId)
    ? currentSelectedRunId
    : undefined;

  if (currentSelectedRunId && !runs.some((run) => run.id === currentSelectedRunId)) {
    currentSelectedRunId = undefined;
  }

  return {
    history: runs,
    selectedRunId: selected,
    statusText: currentStatusText,
    projectPath: currentProjectPath,
    canCancel: !!activeRunId,
  };
}

async function startAnalysis(refresh: () => void): Promise<void> {
  if (activeRunId) {
    currentStatusText = 'A SmartDepTest run is already in progress.';
    return;
  }

  const settings = getSmartDepTestSettings();
  const componentPath = settings.componentPath || resolveConfiguredPath('../SmartDepTest');
  const validation = validateComponentInstallation(componentPath);

  if (!validation.isValid) {
    const projectPath = await chooseTargetProject(settings.projectPath, undefined);
    const errorText = validation.errors.join('\n');
    const run = history.addRun({
      status: 'failed',
      summary: 'Configuration validation failed',
      projectPath: projectPath || '',
      stdout: '',
      stderr: errorText,
      report: '',
      error: errorText,
      exitCode: 1,
      startedAt: new Date().toISOString(),
      completedAt: new Date().toISOString(),
      durationMs: 0,
    });
    currentSelectedRunId = run.id;
    currentStatusText = 'Configuration validation failed.';
    void vscode.window.showErrorMessage(`SmartDepTest configuration validation failed:\n${errorText}`);
    refresh();
    return;
  }

  const projectPath = await chooseTargetProject(settings.projectPath, undefined);
  if (!projectPath) {
    currentStatusText = 'No target project was selected. Analysis cancelled.';
    return;
  }

  const projectValidationError = validateTargetMavenProject(projectPath);
  if (projectValidationError) {
    const now = new Date().toISOString();
    const run = history.addRun({
      status: 'failed',
      summary: 'Target project validation failed',
      projectPath,
      stdout: '',
      stderr: projectValidationError,
      report: projectValidationError,
      error: projectValidationError,
      exitCode: 1,
      startedAt: now,
      completedAt: now,
      durationMs: 0,
    });
    currentSelectedRunId = run.id;
    currentProjectPath = projectPath;
    currentStatusText = 'Target project validation failed.';
    refresh();
    void vscode.window.showErrorMessage(projectValidationError);
    return;
  }

  currentProjectPath = projectPath;
  currentStatusText = `Running analysis for ${projectPath}`;

  const run = history.addRun({
    status: 'running',
    summary: 'SmartDepTest is running',
    projectPath,
    stdout: '',
    stderr: '',
    report: '',
    error: '',
    startedAt: new Date().toISOString(),
  });
  activeRunId = run.id;
  activeRunCancelled = false;
  currentSelectedRunId = run.id;
  outputChannel?.clear();
  outputChannel?.appendLine(`Starting analysis of ${projectPath}`);
  outputChannel?.appendLine(`SmartDepTest component: ${componentPath}`);
  outputChannel?.show(true);
  refresh();
  let refreshTimer: NodeJS.Timeout | undefined;
  const scheduleRefresh = (): void => {
    if (refreshTimer) {
      return;
    }

    refreshTimer = setTimeout(() => {
      refreshTimer = undefined;
      refresh();
    }, 300);
  };

  const result = await executeSmartDepTest(componentPath, projectPath, settings.javaPath, settings.timeoutMs, (child) => {
    activeProcess = child;
    refresh();
  }, (stream, chunk) => {
    const currentRun = history.getById(run.id);
    if (!currentRun) {
      return;
    }

    if (stream === 'stdout') {
      currentRun.stdout += chunk;
      outputChannel?.append(chunk);
    } else {
      currentRun.stderr += chunk;
      outputChannel?.append(`[stderr] ${chunk}`);
    }
    currentRun.report = buildFinalReport(currentRun);
    currentStatusText = `Running analysis for ${projectPath} — received ${currentRun.stdout.length.toLocaleString()} stdout and ${currentRun.stderr.length.toLocaleString()} stderr characters`;
    scheduleRefresh();
  });
  if (refreshTimer) {
    clearTimeout(refreshTimer);
  }
  let finalStatus: 'success' | 'failed' | 'cancelled' = activeRunCancelled || result.status === 'cancelled'
    ? 'cancelled'
    : result.status === 'success' && !hasEngineError(result.stdout)
      ? 'success'
      : 'failed';
  let finalError = result.stderr || '';
  let htmlReportPath: string | undefined;
  let htmlReportOpenError: string | undefined;
  if (finalStatus === 'success') {
    try {
      if (!result.impactGraphJson || !result.coverageSelectionJson) {
        const missing = [
          !result.impactGraphJson ? 'smartdeptest-impact-graph.json' : '',
          !result.coverageSelectionJson ? 'smartdeptest-coverage-selection.json' : '',
        ].filter(Boolean);
        throw new Error(`The current analysis did not produce updated structured result file(s): ${missing.join(', ')}.`);
      }
      const timestamp = new Date().toISOString();
      const reportResult = await generateAndOpenHtmlImpactReport(
        {
          projectPath,
          timestamp,
          impactGraphJson: result.impactGraphJson,
          coverageSelectionJson: result.coverageSelectionJson,
          stdout: result.stdout,
          stderr: result.stderr,
        },
        `smartdeptest-impact-report-${run.sequence}-${Date.now()}.html`,
        finalStatus,
        async (reportPath) => vscode.env.openExternal(vscode.Uri.file(reportPath)),
      );
      htmlReportPath = reportResult.reportPath;
      htmlReportOpenError = reportResult.openError;
      if (!htmlReportPath) {
        throw new Error('The successful analysis did not produce an HTML report path.');
      }
    } catch (error) {
      finalStatus = 'failed';
      const message = error instanceof Error ? error.message : String(error);
      finalError = [finalError, `HTML impact report generation failed: ${message}`].filter(Boolean).join('\n');
    }
  }
  if (hasEngineError(result.stdout)) {
    finalError = [finalError, firstEngineError(result.stdout)].filter(Boolean).join('\n');
  }
  const finalReport = buildFinalReport(result);
  const finalSummary = finalStatus === 'success' ? 'Analysis completed successfully' : finalStatus === 'cancelled' ? 'Analysis was cancelled' : 'Analysis failed';

  history.updateRun(run.id, {
    status: finalStatus,
    summary: finalSummary,
    stdout: result.stdout,
    stderr: result.stderr,
    report: finalReport,
    error: finalError || (finalStatus === 'failed' ? 'SmartDepTest analysis failed.' : ''),
    impactGraphJson: result.impactGraphJson,
    coverageSelectionJson: result.coverageSelectionJson,
    htmlReportPath,
    exitCode: result.exitCode,
    completedAt: new Date().toISOString(),
    durationMs: Date.now() - new Date(run.startedAt).getTime(),
  });

  activeRunId = undefined;
  activeProcess = undefined;
  activeRunCancelled = false;
  currentStatusText = finalStatus === 'success'
    ? 'Analysis completed successfully. HTML impact report generated.'
    : finalStatus === 'cancelled'
      ? 'Analysis cancelled.'
      : 'Analysis failed. Review the report for details.';
  currentSelectedRunId = run.id;
  refresh();

  if (result.artifactWarnings.length > 0) {
    void vscode.window.showWarningMessage(result.artifactWarnings.join('\n'));
  }

  if (finalStatus === 'success' && htmlReportPath) {
    if (htmlReportOpenError) {
      currentStatusText = `Analysis completed; report generated at ${htmlReportPath}, but opening it failed.`;
      refresh();
      void vscode.window.showWarningMessage(`The HTML impact report was generated but could not be opened automatically: ${htmlReportOpenError}`);
    } else {
      void vscode.window.showInformationMessage(`Analysis completed successfully. HTML impact report opened: ${htmlReportPath}`);
    }
  } else if (finalStatus === 'failed') {
    void vscode.window.showErrorMessage(`SmartDepTest analysis failed: ${finalError || 'Review the session report for details.'}`);
  } else if (finalStatus === 'cancelled') {
    void vscode.window.showInformationMessage('SmartDepTest analysis was cancelled.');
  }

  return;
}

function buildFinalReport(result: { stdout: string; stderr: string }): string {
  if (result.stderr.length === 0) {
    return result.stdout;
  }

  if (result.stdout.length === 0) {
    return `stderr:\n${result.stderr}`;
  }

  return `${result.stdout}${result.stdout.endsWith('\n') ? '' : '\n'}--- STDERR ---\n${result.stderr}`;
}

function cancelActiveRun(): void {
  if (!activeRunId) {
    return;
  }

  currentStatusText = 'Cancelling SmartDepTest run...';
  activeRunCancelled = true;
  if (activeProcess) {
    cancelRunningProcess(activeProcess);
  }

  currentStatusText = activeProcess ? 'Cancelling SmartDepTest run...' : 'Cancelling before process startup...';
}

async function saveReport(runId: string, kind: ReportExportKind): Promise<void> {
  if (runId !== currentSelectedRunId) {
    return;
  }

  const run = history.getById(runId);
  if (!run) {
    return;
  }

  const content = getExportContent(run, kind);
  if (content === undefined || content.length === 0) {
    return;
  }

  const destinationDirectory = run.projectPath && fs.existsSync(run.projectPath)
    ? run.projectPath
    : vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  const defaultUri = destinationDirectory
    ? vscode.Uri.file(path.join(destinationDirectory, getExportFilename(run, kind)))
    : undefined;
  const isText = kind === 'text';

  try {
    const destination = await vscode.window.showSaveDialog({
      defaultUri,
      saveLabel: isText ? 'Save Report' : 'Export JSON',
      filters: isText ? { 'Text report': ['txt'] } : { 'JSON report': ['json'] },
    });
    if (!destination) {
      return;
    }

    const outputPath = ensureExportExtension(destination.fsPath, kind);
    await fs.promises.writeFile(outputPath, content, { encoding: 'utf8' });
    void vscode.window.showInformationMessage(`Saved report to ${outputPath}.`);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    void vscode.window.showErrorMessage(`Could not save the selected report: ${message}`);
  }
}

async function openHtmlReport(runId: string): Promise<void> {
  if (runId !== currentSelectedRunId) {
    return;
  }
  const run = history.getById(runId);
  if (!run?.htmlReportPath) {
    void vscode.window.showWarningMessage('No HTML impact report is available for the selected run.');
    return;
  }
  try {
    await openExistingHtmlImpactReport(
      run.htmlReportPath,
      async (reportPath) => vscode.env.openExternal(vscode.Uri.file(reportPath)),
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    void vscode.window.showErrorMessage(`Could not open the selected HTML impact report: ${message}`);
  }
}

async function chooseTargetProject(configuredPath: string | undefined, fallbackWorkspacePath: string | undefined): Promise<string> {
  if (configuredPath && configuredPath.trim().length > 0) {
    const resolved = resolveConfiguredPath(configuredPath);
    if (fs.existsSync(resolved) && fs.statSync(resolved).isDirectory()) {
      return resolved;
    }
  }

  const workspaceFolder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? fallbackWorkspacePath ?? '';
  if (workspaceFolder && fs.existsSync(workspaceFolder) && fs.statSync(workspaceFolder).isDirectory()) {
    return workspaceFolder;
  }

  const selection = await vscode.window.showOpenDialog({
    canSelectFiles: false,
    canSelectFolders: true,
    canSelectMany: false,
    title: 'Choose the Maven Git project to analyze',
  });

  return selection?.[0]?.fsPath ?? '';
}

function hasEngineError(stdout: string): boolean {
  return /(?:^|\r?\n)ERROR:/m.test(stdout);
}

function firstEngineError(stdout: string): string {
  return stdout.split(/\r?\n/).find((line) => line.startsWith('ERROR:')) ?? 'SmartDepTest reported an analysis error.';
}

async function openSourceLocation(runId: string, sourcePath: string, lineNumber: unknown): Promise<void> {
  const run = history.getById(runId);
  if (!run || runId !== currentSelectedRunId) {
    return;
  }

  try {
    const result = await navigateToSource(run.projectPath, sourcePath, lineNumber, {
      openDocument: async (filePath) => vscode.workspace.openTextDocument(vscode.Uri.file(filePath)),
      getLineCount: (document) => document.lineCount,
      showDocument: async (document, zeroBasedLine) => {
        const editor = await vscode.window.showTextDocument(document, { preview: true });
        if (zeroBasedLine !== undefined && zeroBasedLine < document.lineCount) {
          const position = new vscode.Position(zeroBasedLine, 0);
          const range = new vscode.Range(position, position);
          editor.selection = new vscode.Selection(position, position);
          editor.revealRange(range, vscode.TextEditorRevealType.InCenter);
        }
      },
    });
    if (!result.opened) {
      void vscode.window.showWarningMessage(result.error);
    } else if (!result.navigatedToLine) {
      void vscode.window.showInformationMessage('Opened the source file; a valid source line was not available.');
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    void vscode.window.showErrorMessage(`Could not open the selected Java source file: ${message}`);
  }
}

export { history };
