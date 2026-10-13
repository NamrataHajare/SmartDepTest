import * as vscode from "vscode";
import * as childProcess from "node:child_process";
import * as crypto from "node:crypto";
import * as fs from "node:fs/promises";
import * as os from "node:os";
import * as path from "node:path";
import { buildStandaloneHtml, buildWebviewHtml, SmartDepReport } from "./report";
import { SmartDepTestResultsView } from "./resultsView";

const engineJarName = "smartdeptest-component1-1.0-SNAPSHOT.jar";
let analysisRunning = false;

export function activate(context: vscode.ExtensionContext): void {
    const output = vscode.window.createOutputChannel("SmartDepTest");
    const results = new SmartDepTestResultsView();
    const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
    status.command = "smartdeptest.analyzeProject";
    status.text = "$(beaker) SmartDepTest: Ready";
    status.tooltip = "Analyze dependency impact and select regression tests";
    status.show();
    context.subscriptions.push(
        output,
        status,
        vscode.window.registerTreeDataProvider("smartdeptest.results", results),
        vscode.commands.registerCommand("smartdeptest.analyzeProject", (resource?: unknown) =>
            runAnalysis(context, output, status, results, resource)),
        vscode.commands.registerCommand("smartdeptest.runImpactAnalysis", (resource?: unknown) =>
            runAnalysis(context, output, status, results, resource)),
    );
}

export function deactivate(): void {}

async function runAnalysis(
    context: vscode.ExtensionContext,
    output: vscode.OutputChannel,
    status: vscode.StatusBarItem,
    results: SmartDepTestResultsView,
    resource?: unknown,
): Promise<void> {
    if (analysisRunning) {
        void vscode.window.showInformationMessage("SmartDepTest analysis is already running.");
        return;
    }
    const projectRoot = await chooseProjectRoot(resource);
    if (!projectRoot) {
        return;
    }
    analysisRunning = true;
    output.appendLine(`\n=== SmartDepTest analysis: ${projectRoot} ===`);
    output.show(true);
    setStatus(status, results, "running", "$(sync~spin) SmartDepTest: Running");
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
        output.appendLine(`Engine package error: ${message}`);
        setStatus(status, results, "failed", "$(error) SmartDepTest: Failed", message);
        void vscode.window.showErrorMessage(`SmartDepTest engine is unavailable: ${message}`);
        analysisRunning = false;
        return;
    }

    const configuration = vscode.workspace.getConfiguration("smartdeptest");
    const javaExecutable = configuration.get<string>("javaExecutable", "java");
    const mavenExecutable = configuration.get<string>("mavenExecutable", "mvn");
    const timeoutMinutes = configuration.get<number>("analysisTimeoutMinutes", 20);
    const boundedTimeoutMinutes = Number.isFinite(timeoutMinutes)
        ? Math.max(1, Math.min(120, timeoutMinutes)) : 20;
    let tempDirectory: string | undefined;
    try {
        await verifyPrerequisites(javaExecutable, mavenExecutable, projectRoot);
        tempDirectory = await fs.mkdtemp(path.join(os.tmpdir(), "smartdeptest-"));
        const resultPath = path.join(tempDirectory, "impact-report.json");
        let report: SmartDepReport;
        report = await vscode.window.withProgress({
            location: vscode.ProgressLocation.Notification,
            title: "SmartDepTest dependency impact analysis",
            cancellable: true,
        }, async (progress, token) => {
            progress.report({ message: "Resolving dependency versions and comparing APIs…" });
            await runEngine(
                javaExecutable,
                mavenExecutable,
                jarPath,
                resultPath,
                projectRoot,
                output,
                boundedTimeoutMinutes * 60_000,
                token,
                message => progress.report({ message }),
            );
            progress.report({ message: "Loading structured impact results…" });
            const raw = await fs.readFile(resultPath, "utf8");
            const parsed: unknown = JSON.parse(raw);
            return validateReport(parsed);
        });
        showReport(context, projectRoot, report);
        results.setCompleted(report);
        if (report.dependencyUpdate.status === "PARTIAL") {
            const detail = report.warnings[0] ?? "Some project bytecode could not be analyzed.";
            setStatus(status, results, "failed", "$(warning) SmartDepTest: Incomplete", detail);
            output.appendLine(
                `Analysis incomplete. ${report.tests.length} unique tests selected; review report warnings.`,
            );
            void vscode.window.showWarningMessage(
                "SmartDepTest analysis is incomplete. Review the report warnings before relying on test selection.",
            );
        } else {
            setStatus(status, results, "completed", "$(check) SmartDepTest: Complete");
            output.appendLine(`Analysis completed. ${report.tests.length} unique tests selected.`);
            void vscode.window.showInformationMessage(
                `SmartDepTest analysis complete: ${report.tests.length} unique tests selected.`,
            );
        }
    } catch (error) {
        const message = formatAnalysisError(error);
        output.appendLine(`Analysis failed: ${message}`);
        setStatus(status, results, "failed", "$(error) SmartDepTest: Failed", message);
        if (message !== "Analysis cancelled.") {
            output.show(true);
            void vscode.window.showErrorMessage(`SmartDepTest analysis failed: ${message}`);
        } else {
            setStatus(status, results, "cancelled", "$(circle-slash) SmartDepTest: Cancelled");
        }
    } finally {
        if (tempDirectory) {
            await fs.rm(tempDirectory, { recursive: true, force: true }).catch(error => {
                output.appendLine(`Temporary report cleanup failed: ${String(error)}`);
            });
        }
        analysisRunning = false;
    }
}

async function chooseProjectRoot(resource?: unknown): Promise<string | undefined> {
    if (resource instanceof vscode.Uri) {
        const selectedPath = resource.fsPath;
        const root = path.basename(selectedPath).toLowerCase() === "pom.xml"
            ? path.dirname(selectedPath) : selectedPath;
        if (await hasPom(root)) {
            return root;
        }
        void vscode.window.showErrorMessage(`No pom.xml was found at the selected project root: ${root}`);
        return undefined;
    }
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
    if (!await hasPom(root)) {
        void vscode.window.showErrorMessage(
            `No pom.xml was found at the selected project root: ${root}`,
        );
        return undefined;
    }
    return root;
}

async function hasPom(root: string): Promise<boolean> {
    try {
        return (await fs.stat(path.join(root, "pom.xml"))).isFile();
    } catch {
        return false;
    }
}

async function verifyPrerequisites(
    javaExecutable: string,
    mavenExecutable: string,
    projectRoot: string,
): Promise<void> {
    try {
        await runCheck("git", ["-C", projectRoot, "rev-parse", "--show-toplevel"]);
    } catch (error) {
        const detail = error instanceof Error ? error.message : String(error);
        if (/not recognized|ENOENT|cannot find|not found/i.test(detail)) {
            throw new Error("Git is not installed or is not available in PATH. Install Git and reopen VS Code.");
        }
        throw new Error("The selected project is not a valid Git repository. Open the repository root or initialize Git.");
    }
    try {
        await runCheck(mavenExecutable, ["-version"]);
    } catch (error) {
        const detail = error instanceof Error ? error.message : String(error);
        throw new Error(
            `Maven could not be started using '${mavenExecutable}'. Install Maven and add it to PATH, ` +
            "or set smartdeptest.mavenExecutable to the full path of mvn.cmd in VS Code settings. " +
            detail,
        );
    }
    let javaVersion: string;
    try {
        javaVersion = await runCheck(javaExecutable, ["-version"]);
    } catch (error) {
        const detail = error instanceof Error ? error.message : String(error);
        throw new Error(`Java could not be started using '${javaExecutable}'. Check smartdeptest.javaExecutable and install a JDK 21 or newer. ${detail}`);
    }
    const versionMatch = /version\s+"(?:1\.)?(\d+)/i.exec(javaVersion);
    if (!versionMatch || Number(versionMatch[1]) < 21) {
        throw new Error(`SmartDepTest requires JDK 21 or newer. Java reported: ${javaVersion.trim() || "an unknown version"}`);
    }
}

// function runCheck(executable: string, args: string[]): Promise<string> {
//     return new Promise((resolve, reject) => {
//         const child = childProcess.spawn(executable, args, { windowsHide: true, shell: false });
//         let stdout = "";
//         let stderr = "";
//         child.stdout.setEncoding("utf8");
//         child.stderr.setEncoding("utf8");
//         child.stdout.on("data", (chunk: string) => { stdout += chunk; });
//         child.stderr.on("data", (chunk: string) => { stderr += chunk; });
//         child.on("error", reject);
//         child.on("close", code => {
//             const result = `${stdout}\n${stderr}`.trim();
//             if (code === 0) {
//                 resolve(result);
//             } else {
//                 reject(new Error(result || `${executable} exited with status ${String(code)}.`));
//             }
//         });
//     });
// }
function runCheck(executable: string, args: string[]): Promise<string> {
    return new Promise((resolve, reject) => {
        const windows = process.platform === "win32";

        const command = windows ? "cmd.exe" : executable;
        const commandArgs = windows
            ? [
                "/d",
                "/s",
                "/c",
                [executable, ...args]
                    .map(quoteForCommandPrompt)
                    .join(" "),
            ]
            : args;

        const child = childProcess.spawn(command, commandArgs, {
            windowsHide: true,
            shell: false,
        });

        let stdout = "";
        let stderr = "";

        child.stdout.setEncoding("utf8");
        child.stderr.setEncoding("utf8");

        child.stdout.on("data", (chunk: string) => {
            stdout += chunk;
        });

        child.stderr.on("data", (chunk: string) => {
            stderr += chunk;
        });

        child.on("error", reject);

        child.on("close", code => {
            const result = `${stdout}\n${stderr}`.trim();

            if (code === 0) {
                resolve(result);
            } else {
                reject(
                    new Error(
                        result || `${executable} exited with status ${String(code)}.`,
                    ),
                );
            }
        });
    });
}

function quoteForCommandPrompt(argument: string): string {
    if (
        argument.length > 0 &&
        !argument.includes('"') &&
        !/[\s&|<>^()]/.test(argument)
    ) {
        return argument;
    }

    return `"${argument.replace(/"/g, '""')}"`;
}
function setStatus(
    status: vscode.StatusBarItem,
    results: SmartDepTestResultsView,
    state: "running" | "completed" | "failed" | "cancelled",
    text: string,
    detail?: string,
): void {
    status.text = text;
    status.tooltip = detail ? `SmartDepTest ${state}: ${detail}` : `SmartDepTest ${state}`;
    results.setState(state, detail);
}

function formatAnalysisError(error: unknown): string {
    const message = error instanceof Error ? error.message : String(error);
    if (/could not resolve|could not find artifact|failed to collect dependencies|BUILD FAILURE/i.test(message)) {
        return `Maven dependency resolution failed. Check the POM, repository credentials, network access, and Maven output. ${message}`;
    }
    return message;
}

function runEngine(
    javaExecutable: string,
    mavenExecutable: string,
    jarPath: string,
    resultPath: string,
    projectRoot: string,
    output: vscode.OutputChannel,
    timeoutMilliseconds: number,
    token: vscode.CancellationToken,
    onProgress: (message: string) => void,
): Promise<void> {
    return new Promise((resolve, reject) => {
        const environment = { ...process.env };
        if (path.isAbsolute(mavenExecutable)) {
            const mavenDirectory = path.dirname(mavenExecutable);
            const currentPath = environment.PATH ?? environment.Path;
            environment.PATH = [mavenDirectory, currentPath].filter(Boolean).join(path.delimiter);
        }
        const child = childProcess.spawn(javaExecutable, [
            "-cp",
            jarPath,
            "smartdeptest.Main",
            "--json",
            resultPath,
            projectRoot,
        ], {
            cwd: projectRoot,
            env: environment,
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
            output.append(chunk);
            const latest = chunk.trim().split(/\r?\n/).filter(Boolean).at(-1);
            if (latest) {
                onProgress(latest);
            }
        });
        child.stderr.on("data", (chunk: string) => {
            stderr = (stderr + chunk).slice(-16_000);
            output.append(chunk);
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
