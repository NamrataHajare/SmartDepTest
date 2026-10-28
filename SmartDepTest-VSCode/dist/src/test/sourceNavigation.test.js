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
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const node_test_1 = __importDefault(require("node:test"));
const strict_1 = __importDefault(require("node:assert/strict"));
const fs = __importStar(require("node:fs"));
const os = __importStar(require("node:os"));
const path = __importStar(require("node:path"));
const sourceNavigation_1 = require("../wrapper/sourceNavigation");
(0, node_test_1.default)('resolves project-relative source paths and rejects missing or escaping files', () => {
    const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-source-navigation-'));
    const source = path.join(project, 'src', 'main', 'java', 'demo', 'Service.java');
    fs.mkdirSync(path.dirname(source), { recursive: true });
    fs.writeFileSync(source, 'class Service {}', 'utf8');
    try {
        strict_1.default.equal((0, sourceNavigation_1.resolveSourceFile)(project, 'src/main/java/demo/Service.java'), source);
        strict_1.default.equal((0, sourceNavigation_1.resolveSourceFile)(project, '../outside.java'), undefined);
        strict_1.default.equal((0, sourceNavigation_1.resolveSourceFile)(project, 'missing.java'), undefined);
    }
    finally {
        fs.rmSync(project, { recursive: true, force: true });
    }
});
(0, node_test_1.default)('opens a source document and navigates to a valid one-based line', async () => {
    const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-source-open-'));
    fs.writeFileSync(path.join(project, 'Example.java'), 'one\ntwo\nthree', 'utf8');
    const shownLines = [];
    try {
        const result = await (0, sourceNavigation_1.navigateToSource)(project, 'Example.java', 2, {
            openDocument: async (filePath) => ({ filePath }),
            getLineCount: () => 3,
            showDocument: async (_document, line) => { shownLines.push(line); },
        });
        strict_1.default.deepEqual(result, { opened: true, navigatedToLine: true });
        strict_1.default.equal(shownLines.length, 1);
        strict_1.default.equal(shownLines[0], 1);
        const missingLine = await (0, sourceNavigation_1.navigateToSource)(project, 'Example.java', undefined, {
            openDocument: async (filePath) => ({ filePath }),
            getLineCount: () => 3,
            showDocument: async (_document, line) => { shownLines.push(line); },
        });
        strict_1.default.deepEqual(missingLine, { opened: true, navigatedToLine: false });
        strict_1.default.deepEqual(shownLines, [1, undefined]);
    }
    finally {
        fs.rmSync(project, { recursive: true, force: true });
    }
});
