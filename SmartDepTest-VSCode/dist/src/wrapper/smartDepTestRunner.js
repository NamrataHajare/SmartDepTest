"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.validateComponentInstallation = validateComponentInstallation;
exports.resolveProjectRoot = resolveProjectRoot;
exports.validateTargetMavenProject = validateTargetMavenProject;
exports.getJsonReportMetadata = getJsonReportMetadata;
exports.captureUpdatedJsonReports = captureUpdatedJsonReports;
exports.executeSmartDepTest = executeSmartDepTest;
exports.cancelRunningProcess = cancelRunningProcess;
const fs = __importStar(require("node:fs"));
const path = __importStar(require("node:path"));
const node_child_process_1 = require("node:child_process");
function validateComponentInstallation(componentPath) {
    const errors = [];
    const warnings = [];
    if (!componentPath || componentPath.trim().length === 0) {
        errors.push('SmartDepTest component path is not configured.');
        return { isValid: false, errors, warnings, classpath: '' };
    }
    const resolved = path.resolve(componentPath);
    if (!fs.existsSync(resolved) || !fs.statSync(resolved).isDirectory()) {
        errors.push(`The configured SmartDepTest component folder does not exist: ${resolved}`);
        return { isValid: false, errors, warnings, classpath: '' };
    }
    const pomPath = path.join(resolved, 'pom.xml');
    if (!fs.existsSync(pomPath)) {
        errors.push(`Missing pom.xml in the SmartDepTest component: ${resolved}`);
    }
    const mainClassesDir = path.join(resolved, 'target', 'classes');
    if (!fs.existsSync(mainClassesDir)) {
        errors.push(`The compiled SmartDepTest classes are missing. Run 'mvn compile' in ${resolved}.`);
    }
    else {
        const mainClass = path.join(mainClassesDir, 'smartdeptest', 'Main.class');
        if (!fs.existsSync(mainClass)) {
            errors.push(`The SmartDepTest main class was not found in ${mainClassesDir}. Run 'mvn compile'.`);
        }
    }
    const dependencyDir = path.join(resolved, 'target', 'dependency');
    if (!fs.existsSync(dependencyDir)) {
        warnings.push(`Runtime dependency staging directory not found: ${dependencyDir}. The component may need to be compiled first.`);
    }
    const classpathEntries = [mainClassesDir];
    if (fs.existsSync(dependencyDir)) {
        const jarFiles = fs.readdirSync(dependencyDir)
            .filter((entry) => entry.endsWith('.jar'))
            .sort()
            .map((entry) => path.join(dependencyDir, entry));
        classpathEntries.push(...jarFiles);
    }
    return {
        isValid: errors.length === 0,
        errors,
        warnings,
        classpath: classpathEntries.filter((entry) => entry && fs.existsSync(entry)).join(path.delimiter),
    };
}
function resolveProjectRoot(projectPath, fallbackWorkspacePath) {
    if (projectPath && projectPath.trim().length > 0) {
        return path.resolve(projectPath);
    }
    if (fallbackWorkspacePath && fallbackWorkspacePath.trim().length > 0) {
        return path.resolve(fallbackWorkspacePath);
    }
    return process.cwd();
}
function validateTargetMavenProject(projectPath) {
    if (!projectPath || projectPath.trim().length === 0) {
        return 'The selected target Maven project path is blank.';
    }
    const resolved = path.resolve(projectPath);
    try {
        if (!fs.statSync(resolved).isDirectory()) {
            return `The selected target project path is not a directory: ${resolved}`;
        }
    }
    catch (error) {
        return `The selected target project path is unavailable: ${resolved}\n${getErrorMessage(error)}`;
    }
    if (!fs.existsSync(path.join(resolved, 'pom.xml'))) {
        return `The selected target project is not a Maven project (pom.xml not found): ${resolved}`;
    }
    return undefined;
}
function getJsonReportMetadata(projectPath) {
    const targetDirectory = path.join(path.resolve(projectPath), 'target');
    const metadata = {};
    for (const filename of ['smartdeptest-impact-graph.json', 'smartdeptest-coverage-selection.json']) {
        const reportPath = path.join(targetDirectory, filename);
        try {
            const stats = fs.statSync(reportPath);
            if (stats.isFile()) {
                metadata[filename] = {
                    size: stats.size,
                    mtimeMs: stats.mtimeMs,
                    ctimeMs: stats.ctimeMs,
                };
            }
        }
        catch (error) {
            if (!isMissingFileError(error)) {
                throw error;
            }
        }
    }
    return metadata;
}
function captureUpdatedJsonReports(projectPath, before) {
    const targetDirectory = path.join(path.resolve(projectPath), 'target');
    const snapshot = { warnings: [] };
    for (const filename of ['smartdeptest-impact-graph.json', 'smartdeptest-coverage-selection.json']) {
        const reportPath = path.join(targetDirectory, filename);
        try {
            const stats = fs.statSync(reportPath);
            if (!stats.isFile()) {
                continue;
            }
            const prior = before[filename];
            if (prior && prior.size === stats.size && prior.mtimeMs === stats.mtimeMs && prior.ctimeMs === stats.ctimeMs) {
                continue;
            }
            const content = fs.readFileSync(reportPath, 'utf8');
            if (filename === 'smartdeptest-impact-graph.json') {
                snapshot.impactGraphJson = content;
            }
            else {
                snapshot.coverageSelectionJson = content;
            }
        }
        catch (error) {
            if (!isMissingFileError(error)) {
                snapshot.warnings.push(`Could not capture ${filename}: ${getErrorMessage(error)}`);
            }
        }
    }
    return snapshot;
}
async function executeSmartDepTest(componentPath, projectPath, javaPath, timeoutMs, onProcessCreated, onOutput) {
    const validation = validateComponentInstallation(componentPath);
    if (!validation.isValid) {
        const message = validation.errors.join('\n');
        return {
            exitCode: 1,
            stdout: '',
            stderr: message,
            status: 'failed',
            timedOut: false,
            child: undefined,
            artifactWarnings: [],
        };
    }
    const executable = javaPath || 'java';
    const workingDirectory = path.resolve(componentPath);
    const args = ['-cp', validation.classpath, 'smartdeptest.Main', projectPath];
    let reportsBefore;
    try {
        reportsBefore = getJsonReportMetadata(projectPath);
    }
    catch (error) {
        return {
            exitCode: 1,
            stdout: '',
            stderr: `Could not inspect the target project's existing JSON reports: ${getErrorMessage(error)}`,
            status: 'failed',
            timedOut: false,
            artifactWarnings: [],
        };
    }
    return new Promise((resolve) => {
        let stdout = '';
        let stderr = '';
        let timedOut = false;
        let cancelled = false;
        const child = (0, node_child_process_1.spawn)(executable, args, {
            cwd: workingDirectory,
            windowsHide: true,
            stdio: ['inherit', 'pipe', 'pipe'],
        });
        if (onProcessCreated) {
            onProcessCreated(child);
        }
        const timer = setTimeout(() => {
            timedOut = true;
            cancelled = true;
            child.kill('SIGTERM');
        }, timeoutMs);
        child.stdout?.on('data', (chunk) => {
            const text = chunk.toString();
            stdout += text;
            onOutput?.('stdout', text);
        });
        child.stderr?.on('data', (chunk) => {
            const text = chunk.toString();
            stderr += text;
            onOutput?.('stderr', text);
        });
        child.on('error', (error) => {
            clearTimeout(timer);
            resolve({
                exitCode: 1,
                stdout,
                stderr: `${stderr}\n${error.message}`,
                status: 'failed',
                timedOut: false,
                child,
                artifactWarnings: [],
            });
        });
        child.on('close', (code, signal) => {
            clearTimeout(timer);
            const effectiveExitCode = code ?? (signal ? 1 : 0);
            const finalStatus = cancelled || timedOut ? 'cancelled' : effectiveExitCode === 0 ? 'success' : 'failed';
            const reports = captureUpdatedJsonReports(projectPath, reportsBefore);
            const finalStderr = timedOut ? `${stderr}\nSmartDepTest timed out after ${timeoutMs} ms.` : stderr;
            resolve({
                exitCode: timedOut ? 124 : effectiveExitCode,
                stdout,
                stderr: finalStderr,
                status: finalStatus,
                timedOut,
                child,
                impactGraphJson: reports.impactGraphJson,
                coverageSelectionJson: reports.coverageSelectionJson,
                artifactWarnings: reports.warnings,
            });
        });
    });
}
function isMissingFileError(error) {
    return typeof error === 'object' && error !== null && 'code' in error && error.code === 'ENOENT';
}
function getErrorMessage(error) {
    return error instanceof Error ? error.message : String(error);
}
function cancelRunningProcess(childProcess) {
    if (!childProcess) {
        return false;
    }
    return childProcess.kill('SIGTERM');
}
