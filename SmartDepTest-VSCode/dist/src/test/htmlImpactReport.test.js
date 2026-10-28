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
const htmlImpactReport_1 = require("../history/htmlImpactReport");
const methodId = 'method:app-method';
const testId = 'demo.ServiceTest#works';
function graph(overrides = {}) {
    return JSON.stringify({
        nodes: [
            { id: 'dependency:org.example:service:1.0', type: 'DEPENDENCY', metadata: { groupId: 'org.example', artifactId: 'service', oldVersion: '1.0', newVersion: '2.0', analysisStatus: 'ANALYZED' } },
            { id: 'api:removed', type: 'API', metadata: { className: 'demo.Service', memberName: 'removed', changeType: 'METHOD_REMOVED', oldVersion: '1.0', newVersion: '2.0', oldSignature: 'removed()', newSignature: 'removed(int)', potentiallyIncompatible: true } },
            { id: methodId, type: 'METHOD', metadata: { className: 'demo.App', methodName: 'call', descriptor: '()V' } },
        ],
        edges: [
            { source: 'dependency:org.example:service:1.0', target: 'api:removed', type: 'PROVIDES' },
            { source: methodId, target: 'api:removed', type: 'USES' },
        ],
        allAffectedMethods: [methodId],
        applicationMethodLocations: [{ methodId, className: 'demo.App', methodName: 'call', descriptor: '()V', sourcePath: 'src/main/java/demo/App.java', lineNumber: 17 }],
        applicationAnalysisSummary: { modulesDiscovered: 1, modulesIncomplete: 0, classFileFailures: 0, classDirectoryFailures: 0 },
        analysisDiagnostics: [],
        ...overrides,
    });
}
function coverage(overrides = {}) {
    return JSON.stringify({
        selectedTests: [testId],
        groupedSelectedTests: [{ affectedMethod: methodId, impactType: 'DIRECT', selectionStatus: 'SELECTED', selectedTests: [testId] }],
        testCaseLocations: [{ testId, testClass: 'demo.ServiceTest', testMethod: 'works', sourcePath: 'src/test/java/demo/ServiceTest.java', lineNumber: 29 }],
        ...overrides,
    });
}
function createReport(projectPath, graphJson = graph(), coverageJson = coverage()) {
    return (0, htmlImpactReport_1.generateHtmlImpactReport)({
        projectPath,
        timestamp: '2026-10-28T05:30:00.000Z',
        impactGraphJson: graphJson,
        coverageSelectionJson: coverageJson,
        stdout: 'Analysis finished.',
    });
}
(0, node_test_1.default)('renders API, application method, selected test and established traceability from structured results', () => {
    const projectPath = path.join(os.tmpdir(), 'smartdeptest-report-project');
    const html = createReport(projectPath);
    strict_1.default.match(html, /Detected dependency API changes<\/span><\/div>/);
    strict_1.default.match(html, /org\.example:service/);
    strict_1.default.match(html, /1\.0 → 2\.0/);
    strict_1.default.match(html, /METHOD_REMOVED/);
    strict_1.default.match(html, /removed\(\) → removed\(int\)/);
    strict_1.default.match(html, /demo\.App\.call\(\)V/);
    strict_1.default.match(html, /demo\.ServiceTest#works/);
    strict_1.default.match(html, /vscode:\/\/file\//);
    strict_1.default.match(html, /App\.java:17/);
    strict_1.default.match(html, /ServiceTest\.java:29/);
    strict_1.default.match(html, /method-to-test groups explicitly present/);
    strict_1.default.match(html, /demo\.Service\.removed \[METHOD_REMOVED\]/);
    strict_1.default.match(html, /demo\.App\.call\(\)V/);
});
(0, node_test_1.default)('encodes Windows source paths and preserves a valid line number in the VS Code link', () => {
    const project = path.join(os.tmpdir(), 'smart deptest');
    const source = path.join('src', 'main', 'java', 'sample #one', 'A & B.java');
    const link = (0, htmlImpactReport_1.createVsCodeSourceLink)(project, source, 42);
    strict_1.default.ok(link);
    strict_1.default.match(link, /^vscode:\/\/file\//);
    strict_1.default.match(link, /sample%20%23one/);
    strict_1.default.match(link, /A%20%26%20B\.java:42$/);
});
(0, node_test_1.default)('shows source path fallback when source or line metadata is missing without inventing line numbers', () => {
    const graphWithoutLocation = JSON.stringify({
        nodes: [{ id: methodId, type: 'METHOD', metadata: { className: 'demo.App', methodName: 'call', descriptor: '()V' } }],
        edges: [],
        allAffectedMethods: [methodId],
        applicationMethodLocations: [{ methodId, sourcePath: 'src/main/java/demo/App.java', lineNumber: null }],
    });
    const coverageWithoutLocation = JSON.stringify({
        selectedTests: [testId],
        testCaseLocations: [{ testId, testClass: 'demo.ServiceTest', testMethod: 'works', sourcePath: '', lineNumber: null }],
    });
    const html = createReport(path.join(os.tmpdir(), 'source-fallback'), graphWithoutLocation, coverageWithoutLocation);
    strict_1.default.match(html, /src\/main\/java\/demo\/App\.java/);
    strict_1.default.match(html, /Open in VS Code<\/a>/);
    strict_1.default.match(html, /Source path unavailable/);
    strict_1.default.doesNotMatch(html, /App\.java:1(?:<|&)/);
});
(0, node_test_1.default)('renders empty and incomplete results with explicit diagnostics and no-impact caveats', () => {
    const emptyGraph = JSON.stringify({
        nodes: [],
        edges: [],
        allAffectedMethods: [],
        applicationAnalysisSummary: { modulesIncomplete: 1 },
        analysisDiagnostics: ['One module could not be scanned.'],
    });
    const emptyCoverage = JSON.stringify({ selectedTests: [], groupedSelectedTests: [], testSelectionNote: 'No JaCoCo coverage data was available.' });
    const html = (0, htmlImpactReport_1.generateHtmlImpactReport)({
        projectPath: path.join(os.tmpdir(), 'empty-report'),
        timestamp: '2026-10-28T05:30:00.000Z',
        impactGraphJson: emptyGraph,
        coverageSelectionJson: emptyCoverage,
        stdout: 'No JaCoCo XML coverage report was found in the Maven modules.',
    });
    strict_1.default.match(html, /Completed with limitations \/ incomplete mappings/);
    strict_1.default.match(html, /No dependency API changes were recorded/);
    strict_1.default.match(html, /No affected application methods were identified/);
    strict_1.default.match(html, /No regression tests were selected/);
    strict_1.default.match(html, /not proof that the dependency update has no impact/);
    strict_1.default.match(html, /One module could not be scanned/);
    strict_1.default.match(html, /No JaCoCo coverage data was available/);
    strict_1.default.match(html, /No JaCoCo XML coverage report was found in the Maven modules/);
    strict_1.default.match(html, /<strong>0<\/strong><span>Selected regression test cases/);
});
(0, node_test_1.default)('escapes project metadata and analysis content before embedding in HTML', () => {
    const maliciousGraph = graph({
        nodes: [
            { id: 'api:x', type: 'API', metadata: { className: '<script>alert(1)</script>', memberName: '<img src=x>', changeType: 'METHOD_REMOVED' } },
        ],
        edges: [],
        allAffectedMethods: [],
    });
    const html = createReport(path.join(os.tmpdir(), '<project>&"'), maliciousGraph, JSON.stringify({ selectedTests: [] }));
    strict_1.default.doesNotMatch(html, /<script>alert/);
    strict_1.default.doesNotMatch(html, /<img src=x>/);
    strict_1.default.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
    strict_1.default.match(html, /&lt;project&gt;&amp;&quot;/);
});
(0, node_test_1.default)('writes the report before attempting to open it and reports browser-opening failures', async () => {
    const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-html-output-'));
    let openedPath = '';
    try {
        const result = await (0, htmlImpactReport_1.createAndOpenHtmlImpactReport)(project, 'impact.html', '<!doctype html>', async (reportPath) => {
            openedPath = reportPath;
            strict_1.default.equal(fs.readFileSync(reportPath, 'utf8'), '<!doctype html>');
            return false;
        });
        strict_1.default.equal(result.reportPath, path.join(project, 'target', 'impact.html'));
        strict_1.default.equal(openedPath, result.reportPath);
        strict_1.default.equal(result.openError, 'The default application did not open the report.');
        await strict_1.default.rejects((0, htmlImpactReport_1.openExistingHtmlImpactReport)(result.reportPath, async () => false), /did not open/);
        const thrownOpen = await (0, htmlImpactReport_1.createAndOpenHtmlImpactReport)(project, 'throws.html', '<!doctype html>', async () => {
            throw new Error('browser unavailable');
        });
        strict_1.default.equal(thrownOpen.openError, 'browser unavailable');
    }
    finally {
        fs.rmSync(project, { recursive: true, force: true });
    }
});
(0, node_test_1.default)('generates and opens a report only for a successful analysis', async () => {
    const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-successful-run-'));
    let openCount = 0;
    const input = {
        projectPath: project,
        timestamp: '2026-10-28T05:30:00.000Z',
        impactGraphJson: graph(),
        coverageSelectionJson: coverage(),
    };
    try {
        const success = await (0, htmlImpactReport_1.generateAndOpenHtmlImpactReport)(input, 'success.html', 'success', async (reportPath) => {
            openCount += 1;
            strict_1.default.match(fs.readFileSync(reportPath, 'utf8'), /SmartDepTest Impact Analysis Report/);
            return true;
        });
        strict_1.default.equal(success.reportPath, path.join(project, 'target', 'success.html'));
        strict_1.default.equal(openCount, 1);
        const failed = await (0, htmlImpactReport_1.generateAndOpenHtmlImpactReport)(input, 'failed.html', 'failed', async () => {
            openCount += 1;
            return true;
        });
        strict_1.default.deepEqual(failed, {});
        strict_1.default.equal(openCount, 1);
        strict_1.default.equal(fs.existsSync(path.join(project, 'target', 'failed.html')), false);
    }
    finally {
        fs.rmSync(project, { recursive: true, force: true });
    }
});
(0, node_test_1.default)('rejects malformed current-run output instead of generating a success-shaped report', () => {
    strict_1.default.throws(() => createReport(path.join(os.tmpdir(), 'malformed'), '{not json', coverage()), /impact graph JSON is invalid/);
    strict_1.default.throws(() => createReport(path.join(os.tmpdir(), 'malformed'), graph(), '[]'), /coverage selection JSON must contain a JSON object/);
});
(0, node_test_1.default)('does not create or open a report for an invalid project directory', async () => {
    const missingProject = path.join(os.tmpdir(), `smartdeptest-invalid-${Date.now()}`);
    await strict_1.default.rejects((0, htmlImpactReport_1.createAndOpenHtmlImpactReport)(missingProject, 'impact.html', '<html></html>', async () => true), /ENOENT/);
});
