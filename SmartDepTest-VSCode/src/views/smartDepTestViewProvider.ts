import * as vscode from 'vscode';
import { AnalysisRun, SidebarState } from '../types/analysis';
import { ReportExportKind } from '../history/reportExports';

export interface SmartDepTestViewHandlers {
  onStartAnalysis: () => void | Promise<void>;
  onCancelAnalysis: () => void | Promise<void>;
  onSelectRun: (runId: string) => void | Promise<void>;
  onExportReport: (runId: string, kind: ReportExportKind) => void | Promise<void>;
  onOpenHtmlReport: (runId: string) => void | Promise<void>;
  onOpenSourceLocation: (runId: string, sourcePath: string, lineNumber: unknown) => void | Promise<void>;
  onRequestState: () => void;
}

export class SmartDepTestViewProvider implements vscode.WebviewViewProvider {
  private view?: vscode.WebviewView;

  public constructor(
    private readonly extensionUri: vscode.Uri,
    private readonly getState: () => SidebarState,
    private readonly handlers: SmartDepTestViewHandlers,
  ) {}

  public resolveWebviewView(webviewView: vscode.WebviewView, _context: vscode.WebviewViewResolveContext, _token: vscode.CancellationToken): void {
    this.view = webviewView;
    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [this.extensionUri],
    };

    webviewView.webview.html = this.getHtml();
    webviewView.webview.onDidReceiveMessage(async (message) => {
      switch (message.type) {
        case 'request-state':
          this.refresh();
          break;
        case 'start-analysis':
          await this.handlers.onStartAnalysis();
          this.refresh();
          break;
        case 'cancel-analysis':
          await this.handlers.onCancelAnalysis();
          this.refresh();
          break;
        case 'select-run':
          if (typeof message.runId === 'string') {
            await this.handlers.onSelectRun(message.runId);
          }
          this.refresh();
          break;
        case 'export-report':
          if (typeof message.runId === 'string'
            && (message.kind === 'text' || message.kind === 'impact-graph' || message.kind === 'coverage-selection')) {
            await this.handlers.onExportReport(message.runId, message.kind);
          }
          this.refresh();
          break;
        case 'open-html-report':
          if (typeof message.runId === 'string') {
            await this.handlers.onOpenHtmlReport(message.runId);
          }
          this.refresh();
          break;
        case 'open-source-location':
          if (typeof message.runId === 'string' && typeof message.sourcePath === 'string') {
            await this.handlers.onOpenSourceLocation(message.runId, message.sourcePath, message.lineNumber);
          }
          break;
        default:
          break;
      }
    });

    this.refresh();
  }

  public refresh(): void {
    if (!this.view) {
      return;
    }

    const state = this.getState();
    this.view.webview.postMessage({ type: 'state', state });
  }

  private getHtml(): string {
    return `<!DOCTYPE html>
    <html lang="en">
      <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <style>
          :root {
            color-scheme: light dark;
            --bg: var(--vscode-sideBar-background);
            --fg: var(--vscode-foreground);
            --muted: var(--vscode-descriptionForeground);
            --button-bg: var(--vscode-button-background);
            --button-fg: var(--vscode-button-foreground);
            --button-hover: var(--vscode-button-hoverBackground);
            --list-bg: var(--vscode-list-activeSelectionBackground);
            --panel-bg: var(--vscode-editor-background);
            --border: var(--vscode-panel-border);
          }
          body {
            margin: 0;
            padding: 12px;
            background: var(--bg);
            color: var(--fg);
            font-family: var(--vscode-font-family);
            display: flex;
            flex-direction: column;
            gap: 12px;
          }
          .section {
            display: flex;
            flex-direction: column;
            gap: 8px;
          }
          .control-row {
            display: flex;
            gap: 8px;
            align-items: center;
          }
          button {
            border: none;
            border-radius: 4px;
            padding: 8px 12px;
            background: var(--button-bg);
            color: var(--button-fg);
            cursor: pointer;
            font-weight: 600;
          }
          button:hover:not(:disabled) {
            background: var(--button-hover);
          }
          button:disabled {
            opacity: 0.6;
            cursor: not-allowed;
          }
          .secondary {
            background: transparent;
            color: var(--fg);
            border: 1px solid var(--border);
          }
          .muted {
            color: var(--muted);
            font-size: 12px;
          }
          .panel {
            background: var(--panel-bg);
            border: 1px solid var(--border);
            border-radius: 6px;
            padding: 8px;
          }
          #historyList {
            display: flex;
            flex-direction: column;
            gap: 6px;
            max-height: 220px;
            overflow-y: auto;
          }
          .history-item {
            width: 100%;
            border: 1px solid var(--border);
            background: transparent;
            border-radius: 4px;
            padding: 8px;
            text-align: left;
            color: var(--fg);
          }
          .history-item.active {
            background: var(--list-bg);
          }
          .history-summary {
            font-size: 12px;
            color: var(--muted);
            margin-top: 4px;
          }
          #reportContent {
            white-space: pre-wrap;
            word-break: break-word;
            font-family: var(--vscode-editor-font-family);
            font-size: 12px;
            line-height: 1.5;
            min-height: 120px;
            max-height: 320px;
            overflow: auto;
          }
          #reportActions {
            display: flex;
            flex-direction: column;
            gap: 6px;
          }
          #analysisResults {
            display: flex;
            flex-direction: column;
            gap: 8px;
          }
          .result-group {
            display: flex;
            flex-direction: column;
            gap: 5px;
          }
          .result-item {
            width: 100%;
            border: 1px solid var(--border);
            border-radius: 4px;
            padding: 7px;
            text-align: left;
            color: var(--fg);
            background: transparent;
            overflow-wrap: anywhere;
          }
          .result-item:not(:disabled) {
            cursor: pointer;
          }
          .result-item:disabled {
            opacity: 0.7;
          }
          .hidden {
            display: none !important;
          }
        </style>
      </head>
      <body>
        <div class="section">
          <div class="control-row">
            <button id="startButton" type="button">Start Analysis</button>
            <button id="cancelButton" type="button" class="secondary hidden">Cancel</button>
          </div>
          <div id="statusText" class="muted">Ready</div>
          <div id="projectPath" class="muted">Project path: not selected</div>
        </div>

        <div class="section">
          <div class="muted">Session History</div>
          <div id="historyList" class="panel"></div>
        </div>

        <div class="section">
          <div class="control-row">
            <div class="muted">Selected report</div>
            <button id="backButton" type="button" class="secondary">Back to history</button>
          </div>
          <div id="selectedRunDetails" class="muted">No run selected.</div>
          <div class="section">
            <div class="muted">Source navigation results</div>
            <div id="analysisResults" class="panel">No structured impact results available.</div>
          </div>
          <pre id="reportContent" class="panel">No report selected.</pre>
          <div id="reportActions">
            <button id="saveReportButton" type="button">Save Report</button>
            <button id="openHtmlReportButton" type="button" class="secondary hidden">Open HTML Impact Report</button>
            <button id="impactGraphButton" type="button" class="secondary hidden">Export Impact Graph JSON</button>
            <button id="coverageSelectionButton" type="button" class="secondary hidden">Export Coverage Selection JSON</button>
          </div>
        </div>

        <script>
          const vscode = acquireVsCodeApi();
          const startButton = document.getElementById('startButton');
          const cancelButton = document.getElementById('cancelButton');
          const statusText = document.getElementById('statusText');
          const projectPath = document.getElementById('projectPath');
          const historyList = document.getElementById('historyList');
          const reportContent = document.getElementById('reportContent');
          const analysisResults = document.getElementById('analysisResults');
          const backButton = document.getElementById('backButton');
          const selectedRunDetails = document.getElementById('selectedRunDetails');
          const saveReportButton = document.getElementById('saveReportButton');
          const openHtmlReportButton = document.getElementById('openHtmlReportButton');
          const impactGraphButton = document.getElementById('impactGraphButton');
          const coverageSelectionButton = document.getElementById('coverageSelectionButton');
          let selectedRunId;

          function parseJson(text) {
            if (!text) return undefined;
            try {
              return JSON.parse(text);
            } catch {
              return undefined;
            }
          }

          function appendResultButton(container, label, location, runId) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'result-item';
            const sourcePath = location && typeof location.sourcePath === 'string' ? location.sourcePath : '';
            const lineNumber = location && Number.isInteger(location.lineNumber) && location.lineNumber > 0
              ? location.lineNumber : undefined;
            button.textContent = label + (sourcePath ? (lineNumber ? ' — line ' + lineNumber : ' — line unavailable') : ' — source unavailable');
            button.disabled = !sourcePath;
            if (sourcePath) {
              button.addEventListener('click', () => {
                vscode.postMessage({
                  type: 'open-source-location',
                  runId,
                  sourcePath,
                  lineNumber,
                });
              });
            }
            container.appendChild(button);
          }

          function renderAnalysisResults(run) {
            analysisResults.replaceChildren();
            const impact = parseJson(run.impactGraphJson);
            const coverage = parseJson(run.coverageSelectionJson);
            if (!impact && !coverage) {
              analysisResults.textContent = 'No structured impact results available.';
              return;
            }

            const graph = impact || coverage;
            if (graph.sourceLocationResolutionError) {
              const locationError = document.createElement('div');
              locationError.className = 'muted';
              locationError.textContent = 'Source locations could not be resolved: ' + graph.sourceLocationResolutionError;
              analysisResults.appendChild(locationError);
            }
            const methods = new Map((graph.applicationMethodLocations || []).map((location) => [location.methodId, location]));
            (coverage && coverage.applicationMethodLocations || []).forEach((location) => methods.set(location.methodId, location));
            const classes = new Map((graph.applicationClassLocations || []).map((location) => [location.classId, location]));
            (coverage && coverage.applicationClassLocations || []).forEach((location) => classes.set(location.classId, location));
            const nodes = new Map((graph.nodes || []).map((node) => [node.id, node]));
            const methodSection = document.createElement('div');
            methodSection.className = 'result-group';
            const methodHeading = document.createElement('strong');
            methodHeading.textContent = 'Potentially affected application methods';
            methodSection.appendChild(methodHeading);
            (graph.allAffectedMethods || []).forEach((methodId) => {
              const node = nodes.get(methodId);
              const metadata = node && node.metadata || {};
              const label = (metadata.className || methodId) + '.' + (metadata.methodName || '') + (metadata.descriptor || '');
              appendResultButton(methodSection, label, methods.get(methodId), run.id);
            });
            if (!graph.allAffectedMethods || graph.allAffectedMethods.length === 0) {
              const empty = document.createElement('div');
              empty.className = 'muted';
              empty.textContent = 'No affected application method was identified; this is not evidence of no dependency impact.';
              methodSection.appendChild(empty);
            }
            analysisResults.appendChild(methodSection);

            const classSection = document.createElement('div');
            classSection.className = 'result-group';
            const classHeading = document.createElement('strong');
            classHeading.textContent = 'Affected application classes';
            classSection.appendChild(classHeading);
            (graph.affectedNodes || []).forEach((classId) => {
              const node = nodes.get(classId);
              if (!node || node.type !== 'CLASS') return;
              const metadata = node.metadata || {};
              appendResultButton(classSection, metadata.className || classId, classes.get(classId), run.id);
            });
            if (!(graph.affectedNodes || []).some((classId) => nodes.get(classId) && nodes.get(classId).type === 'CLASS')) {
              const empty = document.createElement('div');
              empty.className = 'muted';
              empty.textContent = 'No separately affected application class was identified.';
              classSection.appendChild(empty);
            }
            analysisResults.appendChild(classSection);

            const testSection = document.createElement('div');
            testSection.className = 'result-group';
            const testHeading = document.createElement('strong');
            testHeading.textContent = 'Static call-path test candidates (not guaranteed to fail)';
            testSection.appendChild(testHeading);
            const testLocations = new Map((coverage && coverage.testCaseLocations || []).map((location) => [location.testId, location]));
            const mapped = new Set();
            (coverage && coverage.groupedSelectedTests || []).forEach((group) => {
              const heading = document.createElement('div');
              heading.className = 'muted';
              heading.textContent = group.affectedMethod + ' — ' + group.impactType + ' — ' + group.selectionStatus;
              testSection.appendChild(heading);
              (group.selectedTests || []).forEach((testId) => {
                mapped.add(testId);
                appendResultButton(testSection, testId, testLocations.get(testId), run.id);
              });
              if (!group.selectedTests || group.selectedTests.length === 0) {
                const note = document.createElement('div');
                note.className = 'muted';
                note.textContent = group.selectionNote || 'No method-to-test mapping is available.';
                testSection.appendChild(note);
              }
            });
            const reportedTests = coverage && coverage.testCoverageStatuses || [];
            const unmappedTests = reportedTests.filter((test) => !mapped.has(test.testClass + '#' + test.testMethod));
            if (unmappedTests.length > 0) {
              const note = document.createElement('div');
              note.className = 'muted';
              note.textContent = 'Reported test cases with no method-to-test attribution:';
              testSection.appendChild(note);
              unmappedTests.forEach((test) => {
                const testId = test.testClass + '#' + test.testMethod;
                appendResultButton(testSection, testId + ' — ' + test.status, testLocations.get(testId), run.id);
              });
            } else if ((!coverage || !(coverage.groupedSelectedTests || []).length) && !reportedTests.length) {
              const empty = document.createElement('div');
              empty.className = 'muted';
              empty.textContent = coverage && coverage.testSelectionNote
                ? coverage.testSelectionNote
                : 'No candidate test mapping is available.';
              testSection.appendChild(empty);
            }
            analysisResults.appendChild(testSection);
          }

          function renderHistory(history) {
            historyList.innerHTML = '';
            if (!history || history.length === 0) {
              const empty = document.createElement('div');
              empty.className = 'muted';
              empty.textContent = 'No runs yet.';
              historyList.appendChild(empty);
              return;
            }

            history.forEach((run) => {
              const item = document.createElement('button');
              item.type = 'button';
              item.className = 'history-item';
              item.dataset.runId = run.id;
              item.textContent = 'Run ' + run.sequence + ' — ' + run.status;
              const meta = document.createElement('div');
              meta.className = 'history-summary';
              meta.textContent = new Date(run.startedAt).toLocaleString() + ' — ' + (run.summary || 'No summary available');
              item.appendChild(meta);
              item.addEventListener('click', () => {
                vscode.postMessage({ type: 'select-run', runId: run.id });
              });
              historyList.appendChild(item);
            });
          }

          function renderState(state) {
            selectedRunId = state.selectedRunId;
            statusText.textContent = state.statusText;
            projectPath.textContent = 'Project path: ' + (state.projectPath || 'not selected');
            startButton.disabled = state.canCancel;
            startButton.textContent = state.canCancel ? 'Analysis Running...' : 'Start Analysis';
            cancelButton.classList.toggle('hidden', !state.canCancel);
            renderHistory(state.history);

            const selected = state.history.find((run) => run.id === state.selectedRunId);
            if (!selected) {
              selectedRunDetails.textContent = 'No run selected.';
              reportContent.textContent = 'No report selected.';
              analysisResults.textContent = 'No structured impact results available.';
              saveReportButton.disabled = true;
              openHtmlReportButton.classList.add('hidden');
              impactGraphButton.classList.add('hidden');
              coverageSelectionButton.classList.add('hidden');
              historyList.querySelectorAll('.history-item').forEach((node) => node.classList.remove('active'));
              return;
            }

            const text = selected.report || selected.stdout || selected.stderr || selected.error || 'No report available for this run.';
            selectedRunDetails.textContent = 'Run ' + selected.sequence + ' — ' + selected.status + ' — ' + new Date(selected.startedAt).toLocaleString();
            reportContent.textContent = text;
            renderAnalysisResults(selected);
            saveReportButton.disabled = !(selected.report || selected.stdout || selected.stderr || selected.error);
            openHtmlReportButton.classList.toggle('hidden', !selected.htmlReportPath);
            impactGraphButton.classList.toggle('hidden', !selected.impactGraphJson);
            coverageSelectionButton.classList.toggle('hidden', !selected.coverageSelectionJson);
            document.title = 'SmartDepTest - ' + (selected.summary || 'Run ' + selected.sequence);
            historyList.querySelectorAll('.history-item').forEach((node) => {
              node.classList.toggle('active', node.dataset.runId === selected.id);
            });
          }

          startButton.addEventListener('click', () => {
            vscode.postMessage({ type: 'start-analysis' });
          });

          cancelButton.addEventListener('click', () => {
            vscode.postMessage({ type: 'cancel-analysis' });
          });

          backButton.addEventListener('click', () => {
            vscode.postMessage({ type: 'select-run', runId: '' });
          });

          function exportSelected(kind) {
            if (selectedRunId) {
              vscode.postMessage({ type: 'export-report', runId: selectedRunId, kind });
            }
          }

          saveReportButton.addEventListener('click', () => exportSelected('text'));
          openHtmlReportButton.addEventListener('click', () => {
            if (selectedRunId) vscode.postMessage({ type: 'open-html-report', runId: selectedRunId });
          });
          impactGraphButton.addEventListener('click', () => exportSelected('impact-graph'));
          coverageSelectionButton.addEventListener('click', () => exportSelected('coverage-selection'));

          window.addEventListener('message', (event) => {
            const message = event.data;
            if (message.type === 'state') {
              renderState(message.state);
            }
          });

          vscode.postMessage({ type: 'request-state' });
        </script>
      </body>
    </html>`;
  }
}
