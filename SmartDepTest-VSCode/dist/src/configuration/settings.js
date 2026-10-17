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
exports.getSmartDepTestSettings = getSmartDepTestSettings;
exports.resolveConfiguredPath = resolveConfiguredPath;
const vscode = __importStar(require("vscode"));
const path = __importStar(require("node:path"));
function getSmartDepTestSettings() {
    const config = vscode.workspace.getConfiguration('smartDepTest');
    const componentPath = config.get('componentPath', '../SmartDepTest');
    const projectPath = config.get('projectPath', '');
    const javaPath = config.get('javaPath', 'java');
    const timeoutMs = config.get('timeoutMs', 600000);
    return {
        componentPath: resolveConfiguredPath(componentPath),
        projectPath: projectPath ? resolveConfiguredPath(projectPath) : '',
        javaPath,
        timeoutMs,
    };
}
function resolveConfiguredPath(value) {
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
