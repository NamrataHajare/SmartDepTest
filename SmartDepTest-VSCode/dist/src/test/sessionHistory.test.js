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
const path = __importStar(require("node:path"));
const sessionHistory_1 = require("../history/sessionHistory");
const smartDepTestRunner_1 = require("../wrapper/smartDepTestRunner");
const componentPath = path.resolve(__dirname, '../../../../');
(0, node_test_1.default)('SessionHistory stores multiple distinct runs without overwriting earlier ones', () => {
    const history = new sessionHistory_1.SessionHistory();
    const first = history.addRun({
        status: 'running',
        summary: 'First run',
        projectPath: 'C:/repo/project-a',
        stdout: '',
        stderr: '',
        report: '',
        error: '',
        startedAt: new Date('2024-01-01T00:00:00Z').toISOString(),
    });
    const second = history.addRun({
        status: 'success',
        summary: 'Second run',
        projectPath: 'C:/repo/project-b',
        stdout: 'done',
        stderr: '',
        report: 'done',
        error: '',
        startedAt: new Date('2024-01-02T00:00:00Z').toISOString(),
    });
    const runs = history.getAll();
    strict_1.default.equal(runs.length, 2);
    strict_1.default.equal(runs[0].id, second.id);
    strict_1.default.equal(runs[1].id, first.id);
    strict_1.default.equal(runs[0].summary, 'Second run');
    strict_1.default.equal(runs[1].summary, 'First run');
});
(0, node_test_1.default)('Component validation succeeds for the existing SmartDepTest project when compiled', () => {
    const result = (0, smartDepTestRunner_1.validateComponentInstallation)(componentPath);
    strict_1.default.equal(result.isValid, true, result.errors.join('\n'));
    strict_1.default.ok(result.classpath.length > 0);
});
