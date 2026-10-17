import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { captureUpdatedJsonReports, getJsonReportMetadata, resolveProjectRoot, validateComponentInstallation } from '../wrapper/smartDepTestRunner';

test('resolveProjectRoot falls back to the workspace path when configured project path is empty', () => {
  const fallback = 'C:/repo/fallback';
  const resolved = resolveProjectRoot('', fallback);
  assert.equal(path.resolve(fallback), resolved);
});

test('validateComponentInstallation reports a missing directory clearly', () => {
  const result = validateComponentInstallation(path.resolve(__dirname, './does-not-exist'));
  assert.equal(result.isValid, false);
  assert.ok(result.errors.some((err) => err.includes('does not exist')));
});

test('captureUpdatedJsonReports snapshots only the current run files', () => {
  const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-export-test-'));
  const target = path.join(project, 'target');
  fs.mkdirSync(target);
  const impactGraph = path.join(target, 'smartdeptest-impact-graph.json');
  const coverage = path.join(target, 'smartdeptest-coverage-selection.json');

  try {
    fs.writeFileSync(impactGraph, '{\"run\":1}', 'utf8');
    fs.writeFileSync(coverage, '{\"run\":1}', 'utf8');
    const before = getJsonReportMetadata(project);

    fs.writeFileSync(impactGraph, '{\"run\":2,\"impact\":true}', 'utf8');
    fs.writeFileSync(coverage, '{\"run\":2,\"selection\":true}', 'utf8');

    const captured = captureUpdatedJsonReports(project, before);
    assert.equal(captured.impactGraphJson, '{\"run\":2,\"impact\":true}');
    assert.equal(captured.coverageSelectionJson, '{\"run\":2,\"selection\":true}');
    assert.deepEqual(captured.warnings, []);
  } finally {
    fs.rmSync(project, { recursive: true, force: true });
  }
});

test('captureUpdatedJsonReports does not reuse stale JSON from a prior run', () => {
  const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-stale-report-test-'));
  const target = path.join(project, 'target');
  fs.mkdirSync(target);

  try {
    fs.writeFileSync(path.join(target, 'smartdeptest-impact-graph.json'), '{\"old\":true}', 'utf8');
    const before = getJsonReportMetadata(project);
    const captured = captureUpdatedJsonReports(project, before);
    assert.equal(captured.impactGraphJson, undefined);
  } finally {
    fs.rmSync(project, { recursive: true, force: true });
  }
});
