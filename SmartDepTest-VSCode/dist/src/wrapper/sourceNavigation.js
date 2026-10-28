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
exports.resolveSourceFile = resolveSourceFile;
exports.navigateToSource = navigateToSource;
const fs = __importStar(require("node:fs"));
const path = __importStar(require("node:path"));
function resolveSourceFile(projectPath, sourcePath) {
    if (!projectPath || !sourcePath || path.isAbsolute(sourcePath)) {
        return undefined;
    }
    const projectRoot = path.resolve(projectPath);
    const filePath = path.resolve(projectRoot, sourcePath);
    const relativePath = path.relative(projectRoot, filePath);
    if (!relativePath || relativePath === '..' || relativePath.startsWith(`..${path.sep}`) || path.isAbsolute(relativePath)) {
        return undefined;
    }
    try {
        const realRoot = fs.realpathSync(projectRoot);
        const realFile = fs.realpathSync(filePath);
        const realRelativePath = path.relative(realRoot, realFile);
        if (realRelativePath === '..' || realRelativePath.startsWith(`..${path.sep}`) || path.isAbsolute(realRelativePath)) {
            return undefined;
        }
        return fs.statSync(realFile).isFile() ? realFile : undefined;
    }
    catch {
        return undefined;
    }
}
async function navigateToSource(projectPath, sourcePath, lineNumber, adapter) {
    const filePath = resolveSourceFile(projectPath, sourcePath);
    if (!filePath) {
        return { opened: false, error: 'The source file is unavailable or its path is outside the selected project.' };
    }
    const document = await adapter.openDocument(filePath);
    const lineCount = adapter.getLineCount(document);
    const zeroBasedLine = typeof lineNumber === 'number'
        && Number.isInteger(lineNumber)
        && lineNumber > 0
        && lineNumber <= lineCount
        ? lineNumber - 1
        : undefined;
    await adapter.showDocument(document, zeroBasedLine);
    return { opened: true, navigatedToLine: zeroBasedLine !== undefined };
}
