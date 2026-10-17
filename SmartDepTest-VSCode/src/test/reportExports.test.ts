import test from 'node:test';
import assert from 'node:assert/strict';
import { ensureExportExtension, getExportContent, getExportFilename } from '../history/reportExports';
import { AnalysisRun } from '../types/analysis';

function makeRun(sequence: number, report: string, graph?: string): AnalysisRun {
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

test('exports always use the selected run content and sequence-based filenames', () => {
  const oldRun = makeRun(1, 'older output', '{\"run\":1}');
  const latestRun = makeRun(2, 'latest output', '{\"run\":2}');

  assert.equal(getExportContent(oldRun, 'text'), 'older output');
  assert.equal(getExportContent(oldRun, 'impact-graph'), '{\"run\":1}');
  assert.equal(getExportContent(latestRun, 'text'), 'latest output');
  assert.equal(getExportFilename(oldRun, 'text'), 'smartdeptest-report-001.txt');
  assert.equal(getExportFilename(oldRun, 'impact-graph'), 'smartdeptest-impact-graph-001.json');
  assert.equal(getExportContent(oldRun, 'coverage-selection'), undefined);
  assert.equal(ensureExportExtension('C:/reports/custom', 'text'), 'C:/reports/custom.txt');
  assert.equal(ensureExportExtension('C:/reports/custom.txt', 'impact-graph'), 'C:/reports/custom.txt.json');
});
