import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { getSmartDepTestSettings, resolveConfiguredPath } from './configuration/settings';
import { ensureExportExtension, getExportContent, getExportFilename, ReportExportKind } from './history/reportExports';
import { SessionHistory } from './history/sessionHistory';
import { AnalysisRun, SidebarState } from './types/analysis';
import { SmartDepTestViewProvider } from './views/smartDepTestViewProvider';
import { cancelRunningProcess, executeSmartDepTest, resolveProjectRoot, validateComponentInstallation } from './wrapper/smartDepTestRunner';

const history = new SessionHistory();
let activeRunId: string | undefined;
let currentSelectedRunId: string | undefined;
let activeProcess: { kill: (signal?: NodeJS.Signals | number) => boolean } | undefined;
let activeRunCancelled = false;
let currentStatusText = 'Ready';
let currentProjectPath = '';

export function activate(context: vscode.ExtensionContext): void {
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
    refresh();
    return;
  }

  const projectPath = await chooseTargetProject(settings.projectPath, undefined);
  if (!projectPath) {
    currentStatusText = 'No target project was selected. Analysis cancelled.';
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
  refresh();

  const result = await executeSmartDepTest(componentPath, projectPath, settings.javaPath, settings.timeoutMs, (child) => {
    activeProcess = child;
    refresh();
  });
  const finalStatus: AnalysisRun['status'] = activeRunCancelled || result.status === 'cancelled'
    ? 'cancelled'
    : result.status === 'success'
      ? 'success'
      : 'failed';
  const finalReport = buildFinalReport(result);
  const finalSummary = finalStatus === 'success' ? 'Analysis completed successfully' : finalStatus === 'cancelled' ? 'Analysis was cancelled' : 'Analysis failed';

  history.updateRun(run.id, {
    status: finalStatus,
    summary: finalSummary,
    stdout: result.stdout,
    stderr: result.stderr,
    report: finalReport,
    error: result.stderr || (finalStatus === 'failed' ? 'SmartDepTest exited with a non-zero status.' : ''),
    impactGraphJson: result.impactGraphJson,
    coverageSelectionJson: result.coverageSelectionJson,
    exitCode: result.exitCode,
    completedAt: new Date().toISOString(),
    durationMs: Date.now() - new Date(run.startedAt).getTime(),
  });

  activeRunId = undefined;
  activeProcess = undefined;
  activeRunCancelled = false;
  currentStatusText = finalStatus === 'success'
    ? 'Analysis completed successfully.'
    : finalStatus === 'cancelled'
      ? 'Analysis cancelled.'
      : 'Analysis failed. Review the report for details.';
  currentSelectedRunId = run.id;
  refresh();

  if (result.artifactWarnings.length > 0) {
    void vscode.window.showWarningMessage(result.artifactWarnings.join('\n'));
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

export { history };
