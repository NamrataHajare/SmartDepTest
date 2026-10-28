import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  createAndOpenHtmlImpactReport,
  createVsCodeSourceLink,
  generateAndOpenHtmlImpactReport,
  generateHtmlImpactReport,
  openExistingHtmlImpactReport,
} from '../history/htmlImpactReport';

const methodId = 'method:app-method';
const testId = 'demo.ServiceTest#works';

function graph(overrides: Record<string, unknown> = {}): string {
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

function coverage(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    selectedTests: [testId],
    groupedSelectedTests: [{ affectedMethod: methodId, impactType: 'DIRECT', selectionStatus: 'SELECTED', selectedTests: [testId] }],
    testCaseLocations: [{ testId, testClass: 'demo.ServiceTest', testMethod: 'works', sourcePath: 'src/test/java/demo/ServiceTest.java', lineNumber: 29 }],
    ...overrides,
  });
}

function createReport(projectPath: string, graphJson = graph(), coverageJson = coverage()): string {
  return generateHtmlImpactReport({
    projectPath,
    timestamp: '2026-10-28T05:30:00.000Z',
    impactGraphJson: graphJson,
    coverageSelectionJson: coverageJson,
    stdout: 'Analysis finished.',
  });
}

test('renders API, application method, selected test and established traceability from structured results', () => {
  const projectPath = path.join(os.tmpdir(), 'smartdeptest-report-project');
  const html = createReport(projectPath);

  assert.match(html, /Detected dependency API changes<\/span><\/div>/);
  assert.match(html, /org\.example:service/);
  assert.match(html, /1\.0 → 2\.0/);
  assert.match(html, /METHOD_REMOVED/);
  assert.match(html, /removed\(\) → removed\(int\)/);
  assert.match(html, /demo\.App\.call\(\)V/);
  assert.match(html, /demo\.ServiceTest#works/);
  assert.match(html, /vscode:\/\/file\//);
  assert.match(html, /App\.java:17/);
  assert.match(html, /ServiceTest\.java:29/);
  assert.match(html, /method-to-test groups explicitly present/);
  assert.match(html, /demo\.Service\.removed \[METHOD_REMOVED\]/);
  assert.match(html, /demo\.App\.call\(\)V/);
});

test('encodes Windows source paths and preserves a valid line number in the VS Code link', () => {
  const project = path.join(os.tmpdir(), 'smart deptest');
  const source = path.join('src', 'main', 'java', 'sample #one', 'A & B.java');
  const link = createVsCodeSourceLink(project, source, 42);

  assert.ok(link);
  assert.match(link, /^vscode:\/\/file\//);
  assert.match(link, /sample%20%23one/);
  assert.match(link, /A%20%26%20B\.java:42$/);
});

test('shows source path fallback when source or line metadata is missing without inventing line numbers', () => {
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

  assert.match(html, /src\/main\/java\/demo\/App\.java/);
  assert.match(html, /Open in VS Code<\/a>/);
  assert.match(html, /Source path unavailable/);
  assert.doesNotMatch(html, /App\.java:1(?:<|&)/);
});

test('renders empty and incomplete results with explicit diagnostics and no-impact caveats', () => {
  const emptyGraph = JSON.stringify({
    nodes: [],
    edges: [],
    allAffectedMethods: [],
    applicationAnalysisSummary: { modulesIncomplete: 1 },
    analysisDiagnostics: ['One module could not be scanned.'],
  });
  const emptyCoverage = JSON.stringify({ selectedTests: [], groupedSelectedTests: [], testSelectionNote: 'No JaCoCo coverage data was available.' });
  const html = generateHtmlImpactReport({
    projectPath: path.join(os.tmpdir(), 'empty-report'),
    timestamp: '2026-10-28T05:30:00.000Z',
    impactGraphJson: emptyGraph,
    coverageSelectionJson: emptyCoverage,
    stdout: 'No JaCoCo XML coverage report was found in the Maven modules.',
  });

  assert.match(html, /Completed with limitations \/ incomplete mappings/);
  assert.match(html, /No dependency API changes were recorded/);
  assert.match(html, /No affected application methods were identified/);
  assert.match(html, /No regression tests were selected/);
  assert.match(html, /not proof that the dependency update has no impact/);
  assert.match(html, /One module could not be scanned/);
  assert.match(html, /No JaCoCo coverage data was available/);
  assert.match(html, /No JaCoCo XML coverage report was found in the Maven modules/);
  assert.match(html, /<strong>0<\/strong><span>Selected regression test cases/);
});

test('escapes project metadata and analysis content before embedding in HTML', () => {
  const maliciousGraph = graph({
    nodes: [
      { id: 'api:x', type: 'API', metadata: { className: '<script>alert(1)</script>', memberName: '<img src=x>', changeType: 'METHOD_REMOVED' } },
    ],
    edges: [],
    allAffectedMethods: [],
  });
  const html = createReport(path.join(os.tmpdir(), '<project>&"'), maliciousGraph, JSON.stringify({ selectedTests: [] }));

  assert.doesNotMatch(html, /<script>alert/);
  assert.doesNotMatch(html, /<img src=x>/);
  assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.match(html, /&lt;project&gt;&amp;&quot;/);
});

test('writes the report before attempting to open it and reports browser-opening failures', async () => {
  const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-html-output-'));
  let openedPath = '';
  try {
    const result = await createAndOpenHtmlImpactReport(project, 'impact.html', '<!doctype html>', async (reportPath) => {
      openedPath = reportPath;
      assert.equal(fs.readFileSync(reportPath, 'utf8'), '<!doctype html>');
      return false;
    });

    assert.equal(result.reportPath, path.join(project, 'target', 'impact.html'));
    assert.equal(openedPath, result.reportPath);
    assert.equal(result.openError, 'The default application did not open the report.');
    await assert.rejects(openExistingHtmlImpactReport(result.reportPath, async () => false), /did not open/);
    const thrownOpen = await createAndOpenHtmlImpactReport(project, 'throws.html', '<!doctype html>', async () => {
      throw new Error('browser unavailable');
    });
    assert.equal(thrownOpen.openError, 'browser unavailable');
  } finally {
    fs.rmSync(project, { recursive: true, force: true });
  }
});

test('generates and opens a report only for a successful analysis', async () => {
  const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-successful-run-'));
  let openCount = 0;
  const input = {
    projectPath: project,
    timestamp: '2026-10-28T05:30:00.000Z',
    impactGraphJson: graph(),
    coverageSelectionJson: coverage(),
  };
  try {
    const success = await generateAndOpenHtmlImpactReport(input, 'success.html', 'success', async (reportPath) => {
      openCount += 1;
      assert.match(fs.readFileSync(reportPath, 'utf8'), /SmartDepTest Impact Analysis Report/);
      return true;
    });
    assert.equal(success.reportPath, path.join(project, 'target', 'success.html'));
    assert.equal(openCount, 1);

    const failed = await generateAndOpenHtmlImpactReport(input, 'failed.html', 'failed', async () => {
      openCount += 1;
      return true;
    });
    assert.deepEqual(failed, {});
    assert.equal(openCount, 1);
    assert.equal(fs.existsSync(path.join(project, 'target', 'failed.html')), false);
  } finally {
    fs.rmSync(project, { recursive: true, force: true });
  }
});

test('rejects malformed current-run output instead of generating a success-shaped report', () => {
  assert.throws(() => createReport(path.join(os.tmpdir(), 'malformed'), '{not json', coverage()), /impact graph JSON is invalid/);
  assert.throws(() => createReport(path.join(os.tmpdir(), 'malformed'), graph(), '[]'), /coverage selection JSON must contain a JSON object/);
});

test('does not create or open a report for an invalid project directory', async () => {
  const missingProject = path.join(os.tmpdir(), `smartdeptest-invalid-${Date.now()}`);
  await assert.rejects(
    createAndOpenHtmlImpactReport(missingProject, 'impact.html', '<html></html>', async () => true),
    /ENOENT/,
  );
});
