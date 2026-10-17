"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SmartDepTestViewProvider = void 0;
class SmartDepTestViewProvider {
    extensionUri;
    getState;
    handlers;
    view;
    constructor(extensionUri, getState, handlers) {
        this.extensionUri = extensionUri;
        this.getState = getState;
        this.handlers = handlers;
    }
    resolveWebviewView(webviewView, _context, _token) {
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
                default:
                    break;
            }
        });
        this.refresh();
    }
    refresh() {
        if (!this.view) {
            return;
        }
        const state = this.getState();
        this.view.webview.postMessage({ type: 'state', state });
    }
    getHtml() {
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
          <pre id="reportContent" class="panel">No report selected.</pre>
          <div id="reportActions">
            <button id="saveReportButton" type="button">Save Report</button>
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
          const backButton = document.getElementById('backButton');
          const selectedRunDetails = document.getElementById('selectedRunDetails');
          const saveReportButton = document.getElementById('saveReportButton');
          const impactGraphButton = document.getElementById('impactGraphButton');
          const coverageSelectionButton = document.getElementById('coverageSelectionButton');
          let selectedRunId;

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
              saveReportButton.disabled = true;
              impactGraphButton.classList.add('hidden');
              coverageSelectionButton.classList.add('hidden');
              historyList.querySelectorAll('.history-item').forEach((node) => node.classList.remove('active'));
              return;
            }

            const text = selected.report || selected.stdout || selected.stderr || selected.error || 'No report available for this run.';
            selectedRunDetails.textContent = 'Run ' + selected.sequence + ' — ' + selected.status + ' — ' + new Date(selected.startedAt).toLocaleString();
            reportContent.textContent = text;
            saveReportButton.disabled = !(selected.report || selected.stdout || selected.stderr || selected.error);
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
exports.SmartDepTestViewProvider = SmartDepTestViewProvider;
