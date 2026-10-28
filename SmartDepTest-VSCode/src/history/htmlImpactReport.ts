import * as fs from 'node:fs/promises';
import * as path from 'node:path';

type JsonObject = Record<string, unknown>;

export interface HtmlImpactReportInput {
  projectPath: string;
  timestamp: string;
  impactGraphJson: string;
  coverageSelectionJson: string;
  stdout?: string;
  stderr?: string;
}

export type HtmlImpactRunStatus = 'success' | 'failed' | 'cancelled';

interface ApiChangeReport {
  id: string;
  dependencyName: string;
  versionTransition: string;
  changeType: string;
  className: string;
  memberName: string;
  signature: string;
  incompatible: boolean;
  methodIds: string[];
}

interface MethodReport {
  id: string;
  className: string;
  methodName: string;
  descriptor: string;
  sourcePath: string;
  lineNumber?: number;
  apiChanges: string[];
}

interface TestReport {
  id: string;
  testClass: string;
  testMethod: string;
  sourcePath: string;
  lineNumber?: number;
}

export function generateHtmlImpactReport(input: HtmlImpactReportInput): string {
  const graph = parseObject(input.impactGraphJson, 'impact graph');
  const coverage = parseObject(input.coverageSelectionJson, 'coverage selection');
  const nodes = arrayOfObjects(graph.nodes);
  const edges = arrayOfObjects(graph.edges);
  const dependencies = nodes.filter((node) => node.type === 'DEPENDENCY');
  const apiNodes = nodes.filter((node) => node.type === 'API');
  const methodNodes = nodes.filter((node) => node.type === 'METHOD');
  const methodIds = uniqueStrings(graph.allAffectedMethods);
  const methodLocations = locationsById(graph.applicationMethodLocations, 'methodId');
  const testLocations = locationsById(coverage.testCaseLocations, 'testId');
  const methods = methodIds.map((id) => methodReport(id, methodNodes, methodLocations, edges, apiNodes));
  const selectedTestIds = uniqueStrings([
    ...arrayOfStrings(coverage.selectedTests),
    ...arrayOfObjects(coverage.groupedSelectedTests).flatMap((group) => arrayOfStrings(group.selectedTests)),
  ]);
  const tests = selectedTestIds.map((id) => testReport(id, testLocations));
  const apiChanges = apiNodes.map((api) => apiReport(api, dependencies, edges));
  const engineOutput = [input.stdout, input.stderr].filter((value) => !!value).join('\n\n--- STDERR ---\n\n');
  const limitations = collectLimitations(graph, coverage, dependencies, methods, tests, engineOutput);
  const complete = limitations.length === 0;
  const projectName = path.basename(path.resolve(input.projectPath)) || input.projectPath;
  const versionRows = dependencies.map((dependency) => {
    const metadata = objectValue(dependency.metadata);
    return `<tr><td>${escapeHtml(dependencyName(metadata, stringValue(dependency.id)))}</td><td>${escapeHtml(stringValue(metadata.oldVersion) || '—')}</td><td>${escapeHtml(stringValue(metadata.newVersion) || '—')}</td><td>${escapeHtml(stringValue(metadata.analysisStatus) || 'Not reported')}</td></tr>`;
  });
  const methodRows = methods.length > 0
    ? methods.map((method) => `<tr><td><code>${escapeHtml(method.className)}.${escapeHtml(method.methodName)}${escapeHtml(method.descriptor)}</code></td><td>${sourceReference(input.projectPath, method.sourcePath, method.lineNumber)}</td><td>${method.apiChanges.length ? escapeHtml(method.apiChanges.join(', ')) : 'No API edge recorded'}</td></tr>`)
    : ['<tr><td colspan="3" class="empty">No affected application methods were identified in the structured results. This is not proof of no dependency impact.</td></tr>'];
  const testRows = tests.length > 0
    ? tests.map((test) => `<tr><td><code>${escapeHtml(test.testClass)}#${escapeHtml(test.testMethod)}</code></td><td>${sourceReference(input.projectPath, test.sourcePath, test.lineNumber)}</td></tr>`)
    : ['<tr><td colspan="2" class="empty">No regression tests were selected. This is not proof that the dependency update has no impact.</td></tr>'];
  const apiRows = apiChanges.length > 0
    ? apiChanges.map((change) => `<tr><td>${escapeHtml(change.dependencyName)}</td><td>${escapeHtml(change.versionTransition)}</td><td><code>${escapeHtml(change.className)}${change.memberName ? `.${escapeHtml(change.memberName)}` : ''}</code></td><td>${escapeHtml(change.changeType)}</td><td>${escapeHtml(change.signature || '—')}</td><td>${change.incompatible ? 'Potentially incompatible' : 'Not marked incompatible'}</td><td>${change.methodIds.length ? escapeHtml(change.methodIds.map((id) => methodLabel(id, methodNodes)).join(', ')) : 'No affected method edge recorded'}</td></tr>`)
    : ['<tr><td colspan="7" class="empty">No dependency API changes were recorded.</td></tr>'];
  const traceabilityRows = renderTraceability(
    apiChanges,
    methods,
    arrayOfObjects(coverage.groupedSelectedTests),
    arrayOfObjects(coverage.methodTestMapping),
  );
  const diagnosticItems = limitations.length
    ? limitations.map((item) => `<li>${escapeHtml(item)}</li>`).join('')
    : '<li>No diagnostics or unresolved mappings were reported in the structured results.</li>';
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SmartDepTest Impact Analysis — ${escapeHtml(projectName)}</title>
<style>
:root{color-scheme:light;--ink:#172b4d;--muted:#52647a;--line:#d7e0ea;--paper:#fff;--wash:#f3f6fa;--blue:#174ea6;--teal:#087e8b;--warn:#9a5600;--red:#a12c2c}
*{box-sizing:border-box}body{margin:0;background:var(--wash);color:var(--ink);font:15px/1.55 "Segoe UI",Arial,sans-serif}
main{max-width:1440px;margin:0 auto;padding:36px 28px 64px}header,.card,section{background:var(--paper);border:1px solid var(--line);border-radius:10px}
header{padding:30px 34px;border-top:5px solid var(--blue)}h1{font-size:30px;line-height:1.2;margin:0 0 10px}h2{font-size:21px;margin:0 0 18px}h3{font-size:16px;margin:18px 0 8px}
.subtitle{color:var(--muted);margin:0 0 20px}.metadata{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:10px 24px}.metadata div{min-width:0}.metadata strong{display:block;color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.06em}.metadata span,.metadata code{overflow-wrap:anywhere}
.status{display:inline-block;border-radius:20px;padding:4px 11px;font-weight:700;background:#e8f4ec;color:#23613b}.status.incomplete{background:#fff1d8;color:var(--warn)}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:14px;margin:20px 0}.card{padding:18px 20px}.card strong{display:block;font-size:30px;color:var(--blue)}.card span{color:var(--muted)}
section{padding:24px;margin-top:18px}.table-wrap{overflow-x:auto;border:1px solid var(--line);border-radius:7px}table{width:100%;border-collapse:collapse;min-width:760px}th,td{text-align:left;vertical-align:top;padding:11px 12px;border-bottom:1px solid var(--line)}th{background:#edf2f8;color:#314663;font-size:12px;text-transform:uppercase;letter-spacing:.04em}tr:last-child td{border-bottom:0}td code{overflow-wrap:anywhere}
a.source{color:var(--blue);font-weight:600;text-decoration:underline;text-decoration-thickness:1px;text-underline-offset:3px}a.source:hover{color:var(--teal)}.fallback{display:block;color:var(--muted);font-size:12px;overflow-wrap:anywhere}.empty{color:var(--muted);font-style:italic;padding:18px}
.note{padding:12px 14px;border-left:4px solid var(--teal);background:#edf8f8;color:#254c53}.diagnostics{margin:0;padding-left:22px}.diagnostics li{margin:5px 0}details{margin-top:14px}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:420px;overflow:auto;background:#f3f6fa;padding:14px;border-radius:6px;color:#314663}
footer{color:var(--muted);font-size:12px;margin:24px 2px}@media(max-width:700px){main{padding:16px 10px 36px}header,section{padding:18px}h1{font-size:24px}}
</style>
</head>
<body><main>
<header>
<h1>SmartDepTest Impact Analysis Report</h1>
<p class="subtitle">Static dependency API impact and regression-test selection results.</p>
<div class="metadata">
<div><strong>Project</strong><span>${escapeHtml(projectName)}</span></div>
<div><strong>Project path</strong><code>${escapeHtml(input.projectPath)}</code></div>
<div><strong>Analysis timestamp</strong><span>${escapeHtml(input.timestamp)}</span></div>
<div><strong>Completion status</strong><span class="status${complete ? '' : ' incomplete'}">${complete ? 'Complete — no reported analysis limitations' : 'Completed with limitations / incomplete mappings'}</span></div>
</div>
</header>
<div class="cards">
<div class="card"><strong>${apiChanges.length}</strong><span>Detected dependency API changes</span></div>
<div class="card"><strong>${methodIds.length}</strong><span>Affected application methods</span></div>
<div class="card"><strong>${selectedTestIds.length}</strong><span>Selected regression test cases</span></div>
</div>
<section><h2>Dependency versions compared</h2><div class="table-wrap"><table><thead><tr><th>Dependency</th><th>Previous version</th><th>Current version</th><th>API analysis</th></tr></thead><tbody>${versionRows.join('') || '<tr><td colspan="4" class="empty">No dependency version comparison was recorded.</td></tr>'}</tbody></table></div></section>
<section><h2>Detected dependency API changes</h2><div class="table-wrap"><table><thead><tr><th>Dependency</th><th>Version transition</th><th>Changed API owner/member</th><th>Change type</th><th>Signature</th><th>Compatibility</th><th>Affected methods established by graph</th></tr></thead><tbody>${apiRows.join('')}</tbody></table></div></section>
<section><h2>Affected application methods</h2><div class="table-wrap"><table><thead><tr><th>Application method</th><th>Source navigation</th><th>Related changed APIs</th></tr></thead><tbody>${methodRows.join('')}</tbody></table></div></section>
<section><h2>Selected regression test cases</h2><p class="note">Selection is static call-path or available coverage-based evidence; it is not runtime test execution or proof of coverage.</p><div class="table-wrap"><table><thead><tr><th>Test case</th><th>Source navigation</th></tr></thead><tbody>${testRows.join('')}</tbody></table></div></section>
<section><h2>Traceability</h2><p class="subtitle">Only dependency/API-to-method graph edges and method-to-test groups explicitly present in the analysis artifacts are shown.</p><div class="table-wrap"><table><thead><tr><th>Changed dependency API</th><th>Affected method evidence</th><th>Selected tests grouped for method</th></tr></thead><tbody>${traceabilityRows}</tbody></table></div></section>
<section><h2>Analysis limitations, unresolved mappings, and diagnostics</h2><ul class="diagnostics">${diagnosticItems}</ul>${stringValue(coverage.testSelectionNote) ? `<p class="note">${escapeHtml(stringValue(coverage.testSelectionNote))}</p>` : ''}${engineOutput ? `<details><summary>Engine output and additional diagnostics</summary><pre>${escapeHtml(engineOutput)}</pre></details>` : ''}</section>
<footer>Generated by SmartDepTest VS Code extension at ${escapeHtml(input.timestamp)}. Open source links use the VS Code URI handler; the displayed path and line are the fallback reference.</footer>
</main></body></html>`;
}

export async function writeHtmlImpactReport(projectPath: string, filename: string, html: string): Promise<string> {
  const projectRoot = path.resolve(projectPath);
  const projectStats = await fs.stat(projectRoot);
  if (!projectStats.isDirectory()) {
    throw new Error(`The target project path is not a directory: ${projectRoot}`);
  }
  if (path.basename(filename) !== filename || !filename.toLowerCase().endsWith('.html')) {
    throw new Error('The HTML report filename must be a simple .html filename.');
  }

  const targetDirectory = path.join(projectRoot, 'target');
  await fs.mkdir(targetDirectory, { recursive: true });
  const reportPath = path.join(targetDirectory, filename);
  const temporaryPath = `${reportPath}.${process.pid}.${Date.now()}.tmp`;
  try {
    await fs.writeFile(temporaryPath, html, { encoding: 'utf8', flag: 'wx' });
    await fs.rename(temporaryPath, reportPath);
  } catch (error) {
    await fs.rm(temporaryPath, { force: true });
    throw error;
  }
  return reportPath;
}

export async function createAndOpenHtmlImpactReport(
  projectPath: string,
  filename: string,
  html: string,
  openExternal: (reportPath: string) => Promise<boolean>,
): Promise<{ reportPath: string; openError?: string }> {
  const reportPath = await writeHtmlImpactReport(projectPath, filename, html);
  try {
    if (!await openExternal(reportPath)) {
      return { reportPath, openError: 'The default application did not open the report.' };
    }
    return { reportPath };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return { reportPath, openError: message };
  }
}

export async function generateAndOpenHtmlImpactReport(
  input: HtmlImpactReportInput,
  filename: string,
  status: HtmlImpactRunStatus,
  openExternal: (reportPath: string) => Promise<boolean>,
): Promise<{ reportPath?: string; openError?: string }> {
  if (status !== 'success') {
    return {};
  }
  const html = generateHtmlImpactReport(input);
  return createAndOpenHtmlImpactReport(input.projectPath, filename, html, openExternal);
}

export async function openExistingHtmlImpactReport(
  reportPath: string,
  openExternal: (reportPath: string) => Promise<boolean>,
): Promise<void> {
  await fs.access(reportPath);
  if (!await openExternal(reportPath)) {
    throw new Error('The default application did not open the report.');
  }
}

export function createVsCodeSourceLink(projectPath: string, sourcePath: string, lineNumber?: number): string | undefined {
  if (!projectPath || !sourcePath || path.isAbsolute(sourcePath)) {
    return undefined;
  }
  const root = path.resolve(projectPath);
  const absolutePath = path.resolve(root, sourcePath);
  const relativePath = path.relative(root, absolutePath);
  if (!relativePath || relativePath === '..' || relativePath.startsWith(`..${path.sep}`) || path.isAbsolute(relativePath)) {
    return undefined;
  }
  const normalizedPath = absolutePath.replace(/\\/g, '/');
  if (normalizedPath.startsWith('//')) {
    return undefined;
  }
  const encodedPath = normalizedPath.split('/').map((segment, index) =>
    index === 0 && /^[A-Za-z]:$/.test(segment) ? segment : encodeURIComponent(segment)).join('/');
  const validLine = Number.isSafeInteger(lineNumber) && (lineNumber ?? 0) > 0 ? `:${lineNumber}` : '';
  return `vscode://file/${encodedPath}${validLine}`;
}

function sourceReference(projectPath: string, sourcePath: string, lineNumber?: number): string {
  if (!sourcePath) {
    return '<span class="fallback">Source path unavailable</span>';
  }
  const link = createVsCodeSourceLink(projectPath, sourcePath, lineNumber);
  const suffix = lineNumber ? `:${lineNumber}` : '';
  const display = `${sourcePath}${suffix}`;
  if (!link) {
    return `<span class="fallback">${escapeHtml(display)} (direct link unavailable)</span>`;
  }
  return `<a class="source" href="${escapeHtml(link)}">${escapeHtml(lineNumber ? `Open in VS Code at line ${lineNumber}` : 'Open in VS Code')}</a><span class="fallback">${escapeHtml(display)}</span>`;
}

function apiReport(api: JsonObject, dependencies: JsonObject[], edges: JsonObject[]): ApiChangeReport {
  const metadata = objectValue(api.metadata);
  const dependencyEdge = edges.find((edge) => edge.type === 'PROVIDES' && edge.target === api.id);
  const dependency = dependencies.find((node) => node.id === dependencyEdge?.source);
  const dependencyMetadata = objectValue(dependency?.metadata);
  const oldVersion = stringValue(metadata.oldVersion) || stringValue(dependencyMetadata.oldVersion);
  const newVersion = stringValue(metadata.newVersion) || stringValue(dependencyMetadata.newVersion);
  const methodIds = uniqueStrings(edges
    .filter((edge) => edge.type === 'USES' && edge.target === api.id)
    .map((edge) => stringValue(edge.source))
    .filter((id) => id.startsWith('method:')));
  return {
    id: stringValue(api.id),
    dependencyName: dependencyName(dependencyMetadata, stringValue(dependency?.id)),
    versionTransition: `${oldVersion || '—'} → ${newVersion || '—'}`,
    changeType: stringValue(metadata.changeType) || 'API change',
    className: stringValue(metadata.className) || stringValue(metadata.internalClassName) || 'Unknown owner',
    memberName: stringValue(metadata.memberName),
    signature: [
      stringValue(metadata.oldSignature),
      stringValue(metadata.newSignature),
    ].filter(Boolean).join(' → ') || stringValue(metadata.signature),
    incompatible: metadata.potentiallyIncompatible === true,
    methodIds,
  };
}

function methodReport(
  id: string,
  methodNodes: JsonObject[],
  locations: Map<string, JsonObject>,
  edges: JsonObject[],
  apiNodes: JsonObject[],
): MethodReport {
  const node = methodNodes.find((candidate) => candidate.id === id);
  const metadata = objectValue(node?.metadata);
  const location = locations.get(id);
  const relatedApiIds = edges
    .filter((edge) => edge.type === 'USES' && edge.source === id)
    .map((edge) => stringValue(edge.target));
  return {
    id,
    className: stringValue(metadata.className) || id,
    methodName: stringValue(metadata.methodName) || '',
    descriptor: stringValue(metadata.descriptor),
    sourcePath: stringValue(location?.sourcePath),
    lineNumber: positiveInteger(location?.lineNumber),
    apiChanges: apiNodes.filter((api) => relatedApiIds.includes(stringValue(api.id)))
      .map((api) => apiDisplay(api)),
  };
}

function testReport(id: string, locations: Map<string, JsonObject>): TestReport {
  const location = locations.get(id);
  const separator = id.lastIndexOf('#');
  return {
    id,
    testClass: stringValue(location?.testClass) || (separator >= 0 ? id.slice(0, separator) : id),
    testMethod: stringValue(location?.testMethod) || (separator >= 0 ? id.slice(separator + 1) : ''),
    sourcePath: stringValue(location?.sourcePath),
    lineNumber: positiveInteger(location?.lineNumber),
  };
}

function renderTraceability(
  apiChanges: ApiChangeReport[],
  methods: MethodReport[],
  groups: JsonObject[],
  mappings: JsonObject[],
): string {
  if (apiChanges.length === 0) {
    return '<tr><td colspan="3" class="empty">No dependency API changes were available to trace.</td></tr>';
  }
  return apiChanges.map((change) => {
    const linkedMethods = change.methodIds.map((id) => methods.find((method) => method.id === id)).filter((method): method is MethodReport => !!method);
    const methodCell = linkedMethods.length
      ? linkedMethods.map((method) => `<div>${escapeHtml(`${method.className}.${method.methodName}${method.descriptor}`)}</div>`).join('')
      : 'No affected-method relationship established';
    const relatedTests = linkedMethods.flatMap((method) => [
      ...groups
        .filter((group) => stringValue(group.affectedMethod) === method.id || stringValue(group.affectedMethod) === `${method.className}.${method.methodName}${method.descriptor}`)
        .flatMap((group) => arrayOfStrings(group.selectedTests)),
      ...mappings
        .filter((mapping) => stringValue(mapping.applicationMethod) === method.id)
        .map((mapping) => `${stringValue(mapping.testClass)}#${stringValue(mapping.testMethod)}`),
    ]);
    const testsCell = relatedTests.length
      ? escapeHtml(uniqueStrings(relatedTests).join(', '))
      : 'No method-to-test relationship established';
    return `<tr><td>${escapeHtml(`${change.dependencyName}: ${apiDisplayFromReport(change)}`)}</td><td>${methodCell}</td><td>${testsCell}</td></tr>`;
  }).join('');
}

function collectLimitations(
  graph: JsonObject,
  coverage: JsonObject,
  dependencies: JsonObject[],
  methods: MethodReport[],
  tests: TestReport[],
  engineOutput: string,
): string[] {
  const limitations = new Set<string>();
  const summary = objectValue(graph.applicationAnalysisSummary);
  for (const field of ['modulesIncomplete', 'classFileFailures', 'classDirectoryFailures']) {
    const count = positiveInteger(summary[field]);
    if (count) limitations.add(`${count} ${field} reported by application bytecode analysis.`);
  }
  for (const diagnostic of arrayOfStrings(graph.analysisDiagnostics)) limitations.add(diagnostic);
  for (const node of dependencies) {
    const metadata = objectValue(node.metadata);
    if (stringValue(metadata.analysisStatus) === 'UNAVAILABLE') {
      limitations.add(`Dependency API analysis unavailable for ${dependencyName(metadata, stringValue(node.id))}.`);
    }
  }
  const locationError = stringValue(graph.sourceLocationResolutionError) || stringValue(coverage.sourceLocationResolutionError);
  if (locationError) limitations.add(`Source-location resolution failed: ${locationError}`);
  const testSelectionNote = stringValue(coverage.testSelectionNote);
  if (testSelectionNote) limitations.add(testSelectionNote);
  for (const group of arrayOfObjects(coverage.groupedSelectedTests)) {
    const note = stringValue(group.selectionNote);
    if (note) limitations.add(note);
  }
  for (const test of arrayOfObjects(coverage.testCoverageStatuses)) {
    const note = stringValue(test.note);
    const status = stringValue(test.status);
    if (note || (status && status !== 'N/A')) {
      limitations.add(`${stringValue(test.testClass)}#${stringValue(test.testMethod)}: ${status}${note ? ` — ${note}` : ''}`.trim());
    }
    for (const line of engineOutput.split(/\r?\n/)) {
      const diagnostic = line.trim();
      if (diagnostic && /\b(?:error|warning|diagnostic|unavailable|not found|failed|incomplete|no .*coverage|no .*test|could not)\b/i.test(diagnostic)) {
        limitations.add(diagnostic);
      }
    }
  }
  if (methods.some((method) => !method.sourcePath)) limitations.add('Source locations are unavailable for one or more affected application methods.');
  if (tests.some((test) => !test.sourcePath)) limitations.add('Source locations are unavailable for one or more selected test cases.');
  return [...limitations];
}

function locationsById(value: unknown, idField: string): Map<string, JsonObject> {
  return new Map(arrayOfObjects(value)
    .map((location) => [stringValue(location[idField]), location] as const)
    .filter(([id]) => id.length > 0));
}

function methodLabel(id: string, nodes: JsonObject[]): string {
  const metadata = objectValue(nodes.find((node) => node.id === id)?.metadata);
  if (!metadata.className && !metadata.methodName) return id;
  return `${stringValue(metadata.className)}.${stringValue(metadata.methodName)}${stringValue(metadata.descriptor)}`;
}

function apiDisplay(api: JsonObject): string {
  return apiDisplayFromReport(apiReport(api, [], []));
}

function apiDisplayFromReport(api: ApiChangeReport): string {
  return `${api.className}${api.memberName ? `.${api.memberName}` : ''} [${api.changeType}]`;
}

function dependencyName(metadata: JsonObject, fallback: string): string {
  const group = stringValue(metadata.groupId);
  const artifact = stringValue(metadata.artifactId);
  return group && artifact ? `${group}:${artifact}` : fallback.replace(/^dependency:/, '');
}

function parseObject(text: string, name: string): JsonObject {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(`The current run's ${name} JSON is invalid: ${detail}`);
  }
  if (!isJsonObject(value)) {
    throw new Error(`The current run's ${name} JSON must contain a JSON object.`);
  }
  return value;
}

function objectValue(value: unknown): JsonObject {
  return isJsonObject(value) ? value : {};
}

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function arrayOfObjects(value: unknown): JsonObject[] {
  return Array.isArray(value) ? value.filter((item): item is JsonObject =>
    typeof item === 'object' && item !== null && !Array.isArray(item)) : [];
}

function arrayOfStrings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function uniqueStrings(value: unknown): string[] {
  const strings = Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
  return [...new Set(strings)];
}

function stringValue(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function positiveInteger(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : undefined;
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  })[character] ?? character);
}
