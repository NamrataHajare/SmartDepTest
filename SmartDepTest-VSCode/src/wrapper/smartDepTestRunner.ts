import * as fs from 'node:fs';
import * as path from 'node:path';
import { ChildProcess, spawn } from 'node:child_process';

export interface ValidationResult {
  isValid: boolean;
  errors: string[];
  warnings: string[];
  classpath: string;
}

export interface SmartDepTestExecutionResult {
  exitCode: number;
  stdout: string;
  stderr: string;
  status: 'success' | 'failed' | 'cancelled';
  timedOut: boolean;
  child?: ChildProcess;
  impactGraphJson?: string;
  coverageSelectionJson?: string;
  artifactWarnings: string[];
}

type JsonReportName = 'smartdeptest-impact-graph.json' | 'smartdeptest-coverage-selection.json';

interface FileMetadata {
  size: number;
  mtimeMs: number;
  ctimeMs: number;
}

type JsonReportMetadata = Partial<Record<JsonReportName, FileMetadata>>;

interface JsonReportSnapshot {
  impactGraphJson?: string;
  coverageSelectionJson?: string;
  warnings: string[];
}

export function validateComponentInstallation(componentPath: string): ValidationResult {
  const errors: string[] = [];
  const warnings: string[] = [];

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
  } else {
    const mainClass = path.join(mainClassesDir, 'smartdeptest', 'Main.class');
    if (!fs.existsSync(mainClass)) {
      errors.push(`The SmartDepTest main class was not found in ${mainClassesDir}. Run 'mvn compile'.`);
    }
  }

  const dependencyDir = path.join(resolved, 'target', 'dependency');
  if (!fs.existsSync(dependencyDir)) {
    warnings.push(`Runtime dependency staging directory not found: ${dependencyDir}. The component may need to be compiled first.`);
  }

  const classpathEntries: string[] = [mainClassesDir];
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

export function resolveProjectRoot(projectPath: string, fallbackWorkspacePath?: string): string {
  if (projectPath && projectPath.trim().length > 0) {
    return path.resolve(projectPath);
  }

  if (fallbackWorkspacePath && fallbackWorkspacePath.trim().length > 0) {
    return path.resolve(fallbackWorkspacePath);
  }

  return process.cwd();
}

export function getJsonReportMetadata(projectPath: string): JsonReportMetadata {
  const targetDirectory = path.join(path.resolve(projectPath), 'target');
  const metadata: JsonReportMetadata = {};

  for (const filename of ['smartdeptest-impact-graph.json', 'smartdeptest-coverage-selection.json'] as const) {
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
    } catch (error) {
      if (!isMissingFileError(error)) {
        throw error;
      }
    }
  }

  return metadata;
}

export function captureUpdatedJsonReports(projectPath: string, before: JsonReportMetadata): JsonReportSnapshot {
  const targetDirectory = path.join(path.resolve(projectPath), 'target');
  const snapshot: JsonReportSnapshot = { warnings: [] };

  for (const filename of ['smartdeptest-impact-graph.json', 'smartdeptest-coverage-selection.json'] as const) {
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
      } else {
        snapshot.coverageSelectionJson = content;
      }
    } catch (error) {
      if (!isMissingFileError(error)) {
        snapshot.warnings.push(`Could not capture ${filename}: ${getErrorMessage(error)}`);
      }
    }
  }

  return snapshot;
}

export async function executeSmartDepTest(
  componentPath: string,
  projectPath: string,
  javaPath: string,
  timeoutMs: number,
  onProcessCreated?: (child: ChildProcess) => void,
): Promise<SmartDepTestExecutionResult> {
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
  let reportsBefore: JsonReportMetadata;
  try {
    reportsBefore = getJsonReportMetadata(projectPath);
  } catch (error) {
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

    const child = spawn(executable, args, {
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
      stdout += chunk.toString();
    });

    child.stderr?.on('data', (chunk) => {
      stderr += chunk.toString();
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
      const finalStatus: SmartDepTestExecutionResult['status'] = cancelled || timedOut ? 'cancelled' : effectiveExitCode === 0 ? 'success' : 'failed';
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

function isMissingFileError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'code' in error && error.code === 'ENOENT';
}

function getErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function cancelRunningProcess(childProcess: { kill: (signal?: NodeJS.Signals | number) => boolean } | undefined): boolean {
  if (!childProcess) {
    return false;
  }

  return childProcess.kill('SIGTERM');
}
