import * as vscode from 'vscode';
import * as path from 'node:path';

export interface SmartDepTestSettings {
  componentPath: string;
  projectPath: string;
  javaPath: string;
  timeoutMs: number;
}

export function getSmartDepTestSettings(): SmartDepTestSettings {
  const config = vscode.workspace.getConfiguration('smartDepTest');
  const componentPath = config.get<string>('componentPath', '../SmartDepTest');
  const projectPath = config.get<string>('projectPath', '');
  const javaPath = config.get<string>('javaPath', 'java');
  const timeoutMs = config.get<number>('timeoutMs', 600000);

  return {
    componentPath: resolveConfiguredPath(componentPath),
    projectPath: projectPath ? resolveConfiguredPath(projectPath) : '',
    javaPath,
    timeoutMs,
  };
}

export function resolveConfiguredPath(value: string): string {
  if (!value || value.trim().length === 0) {
    return '';
  }

  if (path.isAbsolute(value)) {
    return value;
  }

  const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
  if (workspaceFolder) {
    return path.resolve(workspaceFolder.uri.fsPath, value);
  }

  return path.resolve(value);
}
