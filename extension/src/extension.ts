import * as vscode from "vscode";
import * as childProcess from "node:child_process";
import * as crypto from "node:crypto";
import * as fs from "node:fs/promises";
import * as os from "node:os";
import * as path from "node:path";
import { buildStandaloneHtml, buildWebviewHtml, SmartDepReport } from "./report";

const engineJarName = "smartdeptest-component1-1.0-SNAPSHOT.jar";

export function activate(context: vscode.ExtensionContext): void {
    context.subscriptions.push(vscode.commands.registerCommand(
        "smartdeptest.runImpactAnalysis",
        () => runAnalysis(context),
    ));
}

export function deactivate(): void {}

async function runAnalysis(context: vscode.ExtensionContext): Promise<void> {
    const projectRoot = await chooseProjectRoot();
    if (!projectRoot) {
        return;
    }
    const jarPath = path.join(context.extensionPath, "engine", engineJarName);
    const dependencyDirectory = path.join(context.extensionPath, "engine", "dependency");
    try {
        await fs.access(jarPath);
        const dependencies = await fs.readdir(dependencyDirectory);
        if (!dependencies.some(name => name.toLowerCase().endsWith(".jar"))) {
            throw new Error("The SmartDepTest runtime dependency JARs are missing from the extension package.");
        }
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        void vscode.window.showErrorMessage(`SmartDepTest engine is unavailable: ${message}`);
        return;
    }

    const javaExecutable = vscode.workspace.getConfiguration("smartdeptest")
        .get<string>("javaExecutable", "java");
    const timeoutMinutes = vscode.workspace.getConfiguration("smartdeptest")
        .get<number>("analysisTimeoutMinutes", 20);
    const boundedTimeoutMinutes = Number.isFinite(timeoutMinutes)
        ? Math.max(1, Math.min(120, timeoutMinutes)) : 20;
    const tempDirectory = await fs.mkdtemp(path.join(os.tmpdir(), "smartdeptest-"));
    const resultPath = path.join(tempDirectory, "impact-report.json");
    let report: SmartDepReport;
    try {
        report = await vscode.window.withProgress({
            location: vscode.ProgressLocation.Notification,
            title: "SmartDepTest dependency impact analysis",
            cancellable: true,
        }, async (progress, token) => {
            progress.report({ message: "Resolving dependency versions and comparing APIs…" });
            await runEngine(javaExecutable, jarPath, resultPath, projectRoot,
                boundedTimeoutMinutes * 60_000, token, message => progress.report({ message }));
            progress.report({ message: "Loading structured impact results…" });
            const raw = await fs.readFile(resultPath, "utf8");
            const parsed: unknown = JSON.parse(raw);
            return validateReport(parsed);
        });
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        if (message !== "Analysis cancelled.") {
            void vscode.window.showErrorMessage(`SmartDepTest analysis failed: ${message}`);
        }
        return;
    } finally {
        await fs.rm(tempDirectory, { recursive: true, force: true }).catch(error => {
            console.error("SmartDepTest temporary report cleanup failed.", error);
        });
    }

    showReport(context, projectRoot, report);
    void vscode.window.showInformationMessage(
        `SmartDepTest analysis complete: ${report.tests.length} unique tests selected.`,
    );
}

async function chooseProjectRoot(): Promise<string | undefined> {
    const folders = vscode.workspace.workspaceFolders ?? [];
    if (!folders.length) {
        void vscode.window.showErrorMessage("Open a Maven project folder before running SmartDepTest.");
        return undefined;
    }
    let selected = folders[0];
    if (folders.length > 1) {
        const choice = await vscode.window.showWorkspaceFolderPick({
            placeHolder: "Select the Maven Git project to analyze",
        });
        if (!choice) {
            return undefined;
        }
        selected = choice;
    }
    const root = selected.uri.fsPath;
    try {
        const stat = await fs.stat(path.join(root, "pom.xml"));
        if (!stat.isFile()) {
            throw new Error("not a regular file");
        }
    } catch {
        void vscode.window.showErrorMessage(
            `No pom.xml was found at the selected project root: ${root}`,
        );
        return undefined;
    }
    return root;
}

function runEngine(
    javaExecutable: string,
    jarPath: string,
    resultPath: string,
    projectRoot: string,
    timeoutMilliseconds: number,
    token: vscode.CancellationToken,
    onProgress: (message: string) => void,
): Promise<void> {
    return new Promise((resolve, reject) => {
        const child = childProcess.spawn(javaExecutable, [
            "-cp",
            jarPath,
            "smartdeptest.Main",
            "--json",
            resultPath,
            projectRoot,
        ], {
            cwd: projectRoot,
            windowsHide: true,
            shell: false,
        });
        let stderr = "";
        let stdout = "";
        let settled = false;
        let timedOut = false;
        const timeout = setTimeout(() => {
            timedOut = true;
            child.kill();
        }, timeoutMilliseconds);
        const cancellation = token.onCancellationRequested(() => child.kill());
        const finish = (error?: Error) => {
            if (settled) {
                return;
            }
            settled = true;
            clearTimeout(timeout);
            cancellation.dispose();
            if (error) {
                reject(error);
            } else {
                resolve();
            }
        };
        child.stdout.setEncoding("utf8");
        child.stderr.setEncoding("utf8");
        child.stdout.on("data", (chunk: string) => {
            stdout = (stdout + chunk).slice(-16_000);
            const latest = chunk.trim().split(/\r?\n/).filter(Boolean).at(-1);
            if (latest) {
                onProgress(latest);
            }
        });
        child.stderr.on("data", (chunk: string) => {
            stderr = (stderr + chunk).slice(-16_000);
        });
        child.on("error", error => finish(new Error(
            `Unable to start Java executable '${javaExecutable}': ${error.message}`,
        )));
        child.on("close", code => {
            if (token.isCancellationRequested) {
                finish(new Error("Analysis cancelled."));
            } else if (timedOut) {
                finish(new Error(`Analysis exceeded the ${timeoutMilliseconds / 60_000}-minute timeout.`));
            } else if (code !== 0) {
                finish(new Error((stderr || stdout).trim()
                    || `Java analysis exited with status ${String(code)}.`));
            } else {
                finish();
            }
        });
    });
}

function validateReport(value: unknown): SmartDepReport {
    if (!value || typeof value !== "object") {
        throw new Error("The Java engine returned an invalid JSON report.");
    }
    const report = value as Partial<SmartDepReport>;
    if (report.schemaVersion !== 1
        || !Array.isArray(report.dependencies)
        || !Array.isArray(report.changedApis)
        || !Array.isArray(report.affectedMethods)
        || !Array.isArray(report.tests)
        || !report.dependencyUpdate
        || !report.testSummary
        || !Array.isArray(report.warnings)
        || !Array.isArray(report.errors)) {
        throw new Error("The Java engine report does not match the supported schema.");
    }
    return report as SmartDepReport;
}

function showReport(
    context: vscode.ExtensionContext,
    projectRoot: string,
    report: SmartDepReport,
): void {
    const panel = vscode.window.createWebviewPanel(
        "smartdeptest.impactReport",
        "SmartDepTest Impact Report",
        vscode.ViewColumn.One,
        { enableScripts: true, localResourceRoots: [] },
    );
    const nonce = crypto.randomBytes(16).toString("base64");
    panel.webview.html = buildWebviewHtml(report, nonce);
    panel.webview.onDidReceiveMessage(async message => {
        if (!message || typeof message !== "object") {
            return;
        }
        if (message.type === "download") {
            await downloadReport(report);
        } else if (message.type === "open"
            && typeof message.path === "string") {
            await openSource(projectRoot, message.path,
                typeof message.line === "number" ? message.line : null);
        }
    }, undefined, context.subscriptions);
}

async function openSource(
    projectRoot: string,
    sourcePath: string,
    line: number | null,
): Promise<void> {
    try {
        if (!path.isAbsolute(sourcePath)) {
            throw new Error("The analysis returned a non-absolute source path.");
        }
        const rootReal = await fs.realpath(projectRoot);
        const sourceReal = await fs.realpath(sourcePath);
        const relative = path.relative(rootReal, sourceReal);
        if (!relative || relative === ".." || relative.startsWith(`..${path.sep}`)
            || path.isAbsolute(relative)) {
            throw new Error("The source path is outside the selected project.");
        }
        const stat = await fs.stat(sourceReal);
        if (!stat.isFile()) {
            throw new Error("The source location is not a file.");
        }
        const document = await vscode.workspace.openTextDocument(vscode.Uri.file(sourceReal));
        const editor = await vscode.window.showTextDocument(document, vscode.ViewColumn.One);
        if (line !== null && Number.isInteger(line) && line > 0) {
            const position = new vscode.Position(Math.min(line - 1, document.lineCount - 1), 0);
            editor.selection = new vscode.Selection(position, position);
            editor.revealRange(new vscode.Range(position, position), vscode.TextEditorRevealType.InCenter);
        }
    } catch (error) {
        const detail = error instanceof Error ? error.message : String(error);
        void vscode.window.showWarningMessage(`Could not open SmartDepTest source location: ${detail}`);
    }
}

async function downloadReport(report: SmartDepReport): Promise<void> {
    const uri = await vscode.window.showSaveDialog({
        defaultUri: vscode.Uri.file(path.join(
            vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? os.homedir(),
            "smartdeptest-impact-report.html",
        )),
        filters: { "HTML report": ["html"] },
        saveLabel: "Save Impact Report",
    });
    if (!uri) {
        return;
    }
    try {
        await fs.writeFile(uri.fsPath, buildStandaloneHtml(report), {
            encoding: "utf8",
            flag: "w",
        });
        void vscode.window.showInformationMessage(`SmartDepTest report saved to ${uri.fsPath}`);
    } catch (error) {
        const detail = error instanceof Error ? error.message : String(error);
        void vscode.window.showErrorMessage(`Could not save SmartDepTest report: ${detail}`);
    }
}
