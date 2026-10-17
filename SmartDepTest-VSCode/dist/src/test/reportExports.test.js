"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const node_test_1 = __importDefault(require("node:test"));
const strict_1 = __importDefault(require("node:assert/strict"));
const reportExports_1 = require("../history/reportExports");
function makeRun(sequence, report, graph) {
    return {
        id: `run-${sequence}`,
        sequence,
        startedAt: new Date('2024-01-01T00:00:00Z').toISOString(),
        status: 'success',
        projectPath: 'C:/repo',
        summary: `Run ${sequence}`,
        stdout: report,
        stderr: '',
        report,
        impactGraphJson: graph,
        error: '',
    };
}
(0, node_test_1.default)('exports always use the selected run content and sequence-based filenames', () => {
    const oldRun = makeRun(1, 'older output', '{\"run\":1}');
    const latestRun = makeRun(2, 'latest output', '{\"run\":2}');
    strict_1.default.equal((0, reportExports_1.getExportContent)(oldRun, 'text'), 'older output');
    strict_1.default.equal((0, reportExports_1.getExportContent)(oldRun, 'impact-graph'), '{\"run\":1}');
    strict_1.default.equal((0, reportExports_1.getExportContent)(latestRun, 'text'), 'latest output');
    strict_1.default.equal((0, reportExports_1.getExportFilename)(oldRun, 'text'), 'smartdeptest-report-001.txt');
    strict_1.default.equal((0, reportExports_1.getExportFilename)(oldRun, 'impact-graph'), 'smartdeptest-impact-graph-001.json');
    strict_1.default.equal((0, reportExports_1.getExportContent)(oldRun, 'coverage-selection'), undefined);
    strict_1.default.equal((0, reportExports_1.ensureExportExtension)('C:/reports/custom', 'text'), 'C:/reports/custom.txt');
    strict_1.default.equal((0, reportExports_1.ensureExportExtension)('C:/reports/custom.txt', 'impact-graph'), 'C:/reports/custom.txt.json');
});
