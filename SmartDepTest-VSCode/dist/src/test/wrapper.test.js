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
const smartDepTestRunner_1 = require("../wrapper/smartDepTestRunner");
(0, node_test_1.default)('resolveProjectRoot falls back to the workspace path when configured project path is empty', () => {
    const fallback = 'C:/repo/fallback';
    const resolved = (0, smartDepTestRunner_1.resolveProjectRoot)('', fallback);
    strict_1.default.equal(path.resolve(fallback), resolved);
});
(0, node_test_1.default)('validateComponentInstallation reports a missing directory clearly', () => {
    const result = (0, smartDepTestRunner_1.validateComponentInstallation)(path.resolve(__dirname, './does-not-exist'));
    strict_1.default.equal(result.isValid, false);
    strict_1.default.ok(result.errors.some((err) => err.includes('does not exist')));
});
(0, node_test_1.default)('validateTargetMavenProject rejects missing/non-Maven paths and accepts a Maven root', () => {
    const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-target-validation-'));
    const mavenProject = path.join(project, 'maven');
    fs.mkdirSync(mavenProject);
    fs.writeFileSync(path.join(mavenProject, 'pom.xml'), '<project/>', 'utf8');
    try {
        strict_1.default.match((0, smartDepTestRunner_1.validateTargetMavenProject)(path.join(project, 'missing')) ?? '', /unavailable/);
        strict_1.default.match((0, smartDepTestRunner_1.validateTargetMavenProject)(project) ?? '', /pom\.xml not found/);
        strict_1.default.equal((0, smartDepTestRunner_1.validateTargetMavenProject)(mavenProject), undefined);
    }
    finally {
        fs.rmSync(project, { recursive: true, force: true });
    }
});
(0, node_test_1.default)('captureUpdatedJsonReports snapshots only the current run files', () => {
    const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-export-test-'));
    const target = path.join(project, 'target');
    fs.mkdirSync(target);
    const impactGraph = path.join(target, 'smartdeptest-impact-graph.json');
    const coverage = path.join(target, 'smartdeptest-coverage-selection.json');
    try {
        fs.writeFileSync(impactGraph, '{\"run\":1}', 'utf8');
        fs.writeFileSync(coverage, '{\"run\":1}', 'utf8');
        const before = (0, smartDepTestRunner_1.getJsonReportMetadata)(project);
        fs.writeFileSync(impactGraph, '{\"run\":2,\"impact\":true}', 'utf8');
        fs.writeFileSync(coverage, '{\"run\":2,\"selection\":true}', 'utf8');
        const captured = (0, smartDepTestRunner_1.captureUpdatedJsonReports)(project, before);
        strict_1.default.equal(captured.impactGraphJson, '{\"run\":2,\"impact\":true}');
        strict_1.default.equal(captured.coverageSelectionJson, '{\"run\":2,\"selection\":true}');
        strict_1.default.deepEqual(captured.warnings, []);
    }
    finally {
        fs.rmSync(project, { recursive: true, force: true });
    }
});
(0, node_test_1.default)('captureUpdatedJsonReports does not reuse stale JSON from a prior run', () => {
    const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-stale-report-test-'));
    const target = path.join(project, 'target');
    fs.mkdirSync(target);
    try {
        fs.writeFileSync(path.join(target, 'smartdeptest-impact-graph.json'), '{\"old\":true}', 'utf8');
        const before = (0, smartDepTestRunner_1.getJsonReportMetadata)(project);
        const captured = (0, smartDepTestRunner_1.captureUpdatedJsonReports)(project, before);
        strict_1.default.equal(captured.impactGraphJson, undefined);
    }
    finally {
        fs.rmSync(project, { recursive: true, force: true });
    }
});
(0, node_test_1.default)('executeSmartDepTest streams process output as it arrives', async () => {
    const chunks = [];
    const missingProject = path.join(os.tmpdir(), `smartdeptest-missing-project-${Date.now()}`);
    const result = await (0, smartDepTestRunner_1.executeSmartDepTest)(path.resolve(__dirname, '../../../../'), missingProject, 'java', 15000, undefined, (_stream, chunk) => chunks.push(chunk));
    strict_1.default.ok(result.stdout.includes('ERROR:'));
    strict_1.default.ok(chunks.join('').includes('ERROR:'));
});
