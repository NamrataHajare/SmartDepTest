import * as vscode from "vscode";
import { SmartDepReport } from "./report";

class ResultItem extends vscode.TreeItem {
    constructor(
        label: string,
        description?: string,
        readonly children: ResultItem[] = [],
    ) {
        super(label, children.length
            ? vscode.TreeItemCollapsibleState.Collapsed
            : vscode.TreeItemCollapsibleState.None);
        this.description = description;
        this.contextValue = children.length ? "smartdeptest.category" : "smartdeptest.result";
    }
}

export class SmartDepTestResultsView implements vscode.TreeDataProvider<ResultItem> {
    private readonly changeEmitter = new vscode.EventEmitter<ResultItem | undefined>();
    readonly onDidChangeTreeData = this.changeEmitter.event;
    private state = "Ready";
    private detail = "Run SmartDepTest to analyze this workspace.";
    private report?: SmartDepReport;

    setState(state: string, detail?: string): void {
        this.state = state;
        this.detail = detail || `Analysis ${state}.`;
        this.changeEmitter.fire(undefined);
    }

    setCompleted(report: SmartDepReport): void {
        this.report = report;
        this.state = "Complete";
        this.detail = `${report.tests.length} unique tests selected.`;
        this.changeEmitter.fire(undefined);
    }

    getTreeItem(element: ResultItem): vscode.TreeItem {
        return element;
    }

    getChildren(element?: ResultItem): ResultItem[] {
        if (element) {
            return element.children;
        }
        const rows = [new ResultItem(`Status: ${this.state}`, this.detail)];
        if (!this.report) {
            return rows;
        }
        rows.push(
            category("Dependencies", this.report.dependencies.map(item =>
                new ResultItem(`${value(item.groupId)}:${value(item.artifactId)}`,
                    `${value(item.oldVersion)} -> ${value(item.newVersion)} · ${value(item.status)}`))),
            category("Changed APIs", this.report.changedApis.map(item =>
                new ResultItem(`${value(item.className)}.${value(item.memberName || "(class)")}`,
                    value(item.changeType)))),
            category("Affected application methods", this.report.affectedMethods.map(method =>
                new ResultItem(method.displayName, `${method.classification} · ${method.reason}`))),
            category("Selected regression tests", this.report.tests.map(test =>
                new ResultItem(`${test.testClass}#${test.testMethod}`,
                    `${test.classification} · ${test.affectedMethods.length} affected methods`))),
            category("Warnings and errors", [
                ...this.report.warnings.map(message => new ResultItem(message)),
                ...this.report.errors.map(message => new ResultItem(message)),
            ]),
        );
        return rows;
    }
}

function category(label: string, children: ResultItem[]): ResultItem {
    return new ResultItem(`${label} (${children.length})`, undefined, children);
}

function value(value: unknown): string {
    return value === null || value === undefined || value === "" ? "Unavailable" : String(value);
}