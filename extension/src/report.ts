export interface SourceLocation {
    path: string;
    line: number | null;
}

export interface AffectedMethod {
    id: string;
    className: string;
    methodName: string;
    descriptor: string;
    displayName: string;
    classification: string;
    reason: string;
    source: SourceLocation;
}

export interface SelectedTest {
    id: string;
    testClass: string;
    testMethod: string;
    classification: "DIRECT" | "INDIRECT";
    source: SourceLocation;
    affectedMethods: AffectedMethod[];
    callPaths: string[][];
}

export interface SmartDepReport {
    schemaVersion: number;
    projectPath: string;
    dependencyUpdate: {
        commitId: string;
        previousCommitId: string;
        commitMessage: string;
        status: string;
    };
    dependencies: Array<Record<string, unknown>>;
    changedApis: Array<Record<string, unknown>>;
    affectedMethods: AffectedMethod[];
    tests: SelectedTest[];
    testSummary: {
        availableTests: number | null;
        selectedTests: number;
        directlyAffectedTests: number;
        indirectlyAffectedTests: number;
        notSelectedTests: number | null;
        unresolvedMappings: Array<Record<string, unknown>>;
        selectionEvidence: string;
    };
    warnings: string[];
    errors: string[];
    baseline: null | {
        selectedTests: number;
        technique?: string;
        missedRelevantTests?: string[];
        limitations?: string[];
    };
}

export function visibleTestCount(total: number, expanded: boolean): number {
    return expanded ? total : Math.min(3, total);
}

export function escapeHtml(value: unknown): string {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

function safeJson(value: unknown): string {
    return JSON.stringify(value)
        .replace(/</g, "\\u003c")
        .replace(/>/g, "\\u003e")
        .replace(/&/g, "\\u0026")
        .replace(/\u2028/g, "\\u2028")
        .replace(/\u2029/g, "\\u2029");
}

export function buildWebviewHtml(report: SmartDepReport, nonce: string): string {
    const serialized = safeJson(report);
    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
<title>SmartDepTest Impact Report</title>
<style nonce="${nonce}">
body{font-family:var(--vscode-font-family);color:var(--vscode-foreground);background:var(--vscode-editor-background);line-height:1.5;max-width:1100px;margin:0 auto;padding:22px}
h1{font-size:1.55rem;font-weight:600;margin:0 0 4px}h2{font-size:1.15rem;font-weight:600;border-bottom:1px solid var(--vscode-panel-border);padding-bottom:7px;margin-top:28px}
h3{font-size:1rem;margin:0 0 6px}.muted{color:var(--vscode-descriptionForeground)}.toolbar{display:flex;align-items:center;gap:12px;margin:14px 0 20px}
button{font:inherit;color:var(--vscode-button-foreground);background:var(--vscode-button-background);border:0;border-radius:2px;padding:6px 11px;cursor:pointer}
button:hover{background:var(--vscode-button-hoverBackground)}button.secondary{color:var(--vscode-textLink-foreground);background:transparent;padding:2px 4px;text-decoration:underline}
.summary{border-collapse:collapse;width:100%;max-width:700px}.summary th,.summary td{text-align:left;padding:5px 12px 5px 0;border-bottom:1px solid var(--vscode-panel-border)}
.entry{border-bottom:1px solid var(--vscode-panel-border);padding:12px 0}.tag{font-size:.82em;color:var(--vscode-descriptionForeground);margin-left:7px}
.warning{border-left:3px solid var(--vscode-editorWarning-foreground);padding:5px 10px;margin:7px 0}.error{border-left:3px solid var(--vscode-errorForeground);padding:5px 10px;margin:7px 0}
.api-method{padding-left:16px;margin:4px 0}.path{font-family:var(--vscode-editor-font-family);font-size:.92em;overflow-wrap:anywhere}
.test-actions{display:flex;gap:14px;flex-wrap:wrap;margin:4px 0}.callpath{padding-left:16px;margin:3px 0}.empty{padding:10px 0}
</style>
</head>
<body>
<main id="report"></main>
<script nonce="${nonce}">
const report=${serialized};
const vscode=acquireVsCodeApi();
const root=document.getElementById('report');
let expanded=false;
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=String(text);if(cls)n.className=cls;return n;};
const section=(title)=>{const s=el('section');s.appendChild(el('h2',title));root.appendChild(s);return s;};
const open=(loc,label)=>{if(!loc||!loc.path){return;}const b=el('button',label,'secondary');b.addEventListener('click',()=>vscode.postMessage({type:'open',path:loc.path,line:loc.line}));return b;};
const showLocation=(parent,loc)=>{if(loc&&loc.path){const p=el('div',loc.path+(loc.line?' :'+loc.line:''),'muted path');parent.appendChild(p);}};
root.appendChild(el('h1','SmartDepTest dependency impact report'));
root.appendChild(el('div',report.projectPath,'muted path'));
const toolbar=el('div',undefined,'toolbar');
const download=el('button','Download Report');download.addEventListener('click',()=>vscode.postMessage({type:'download'}));toolbar.appendChild(download);
toolbar.appendChild(el('span','Analysis: '+report.dependencyUpdate.status,'muted'));root.appendChild(toolbar);
const depSection=section('Dependency update');
const depTable=el('table',undefined,'summary');const depBody=el('tbody');
[['Revision',report.dependencyUpdate.commitId||'Unavailable'],['Previous revision',report.dependencyUpdate.previousCommitId||'Unavailable'],['Commit message',report.dependencyUpdate.commitMessage||'Unavailable']].forEach(([k,v])=>{const row=el('tr');row.appendChild(el('th',k));row.appendChild(el('td',v));depBody.appendChild(row);});
depTable.appendChild(depBody);depSection.appendChild(depTable);
report.dependencies.forEach(d=>{const e=el('p');e.textContent=d.groupId+':'+d.artifactId+'  '+(d.oldVersion||'(none)')+' → '+(d.newVersion||'(none)')+'  ·  '+d.status;depSection.appendChild(e);if(d.message)depSection.appendChild(el('div',d.message,'muted'));});
const apis=section('Changed APIs and affected application methods');
if(!report.changedApis.length)apis.appendChild(el('p','No changed API records were produced.','empty'));
report.changedApis.forEach(api=>{const e=el('div',undefined,'entry');e.appendChild(el('h3',(api.className||'')+'.'+(api.memberName||'(class)')+'  ·  '+(api.changeType||'API change')));e.appendChild(el('div','Old: '+(api.oldSignature||api.oldDescriptor||'Unavailable')+'    New: '+(api.newSignature||api.newDescriptor||'Unavailable'),'muted'));(api.affectedMethods||[]).forEach(m=>{const line=el('div',undefined,'api-method');line.appendChild(el('div',m.displayName+'  ·  '+m.classification));line.appendChild(el('div',m.reason,'muted'));showLocation(line,m.source);const action=open(m.source,'Open application method');if(action)line.appendChild(action);e.appendChild(line);});apis.appendChild(e);});
const methods=section('Affected application methods');if(!report.affectedMethods.length)methods.appendChild(el('p','No affected application methods were identified.','empty'));report.affectedMethods.forEach(m=>{const e=el('div',undefined,'entry');e.appendChild(el('h3',m.displayName+'  ·  '+m.classification));e.appendChild(el('div',m.reason,'muted'));showLocation(e,m.source);const action=open(m.source,'Open application method');if(action)e.appendChild(action);methods.appendChild(e);});
const tests=section('Selected tests');
const sums=el('table',undefined,'summary');const sumBody=el('tbody');
[['Available tests',report.testSummary.availableTests],['Unique selected',report.testSummary.selectedTests],['Directly affected',report.testSummary.directlyAffectedTests],['Indirectly affected',report.testSummary.indirectlyAffectedTests],['Not selected',report.testSummary.notSelectedTests]].forEach(([k,v])=>{const row=el('tr');row.appendChild(el('th',k));row.appendChild(el('td',v===null?'Not available':v));sumBody.appendChild(row);});
sums.appendChild(sumBody);tests.appendChild(sums);
if(report.testSummary.selectionEvidence)tests.appendChild(el('p',report.testSummary.selectionEvidence,'muted'));
const testList=el('div');tests.appendChild(testList);const toggle=el('button');toggle.className='secondary';
if(!report.tests.length)tests.appendChild(el('p','No tests were selected by static call-path analysis.','empty'));
const renderTests=()=>{testList.replaceChildren();const count=expanded?report.tests.length:Math.min(3,report.tests.length);report.tests.slice(0,count).forEach(t=>{const e=el('article',undefined,'entry');e.appendChild(el('h3',t.testClass+'#'+t.testMethod));e.appendChild(el('span',t.classification,'tag'));showLocation(e,t.source);const actions=el('div',undefined,'test-actions');const testAction=open(t.source,'Open test');if(testAction)actions.appendChild(testAction);e.appendChild(actions);if(t.affectedMethods.length){e.appendChild(el('div','Affected application methods'));t.affectedMethods.forEach(m=>{const row=el('div',undefined,'api-method');row.appendChild(el('div',m.displayName+'  ·  '+m.classification));showLocation(row,m.source);const action=open(m.source,'Open application method');if(action)row.appendChild(action);e.appendChild(row);});}if(t.callPaths.length){e.appendChild(el('div','Static call path evidence'));t.callPaths.forEach(p=>e.appendChild(el('div',p.join(' → '),'callpath path')));}else e.appendChild(el('div','A static test-to-method association was found, but its individual call path was not retained.','muted'));testList.appendChild(e);});toggle.hidden=report.tests.length<=3;if(!toggle.hidden)toggle.textContent=expanded?'Show less':'Show all '+report.tests.length+' tests';};
toggle.addEventListener('click',()=>{expanded=!expanded;renderTests();});tests.appendChild(toggle);renderTests();
const unresolved=section('Unresolved mappings');if(!report.testSummary.unresolvedMappings.length)unresolved.appendChild(el('p','No unresolved affected-method mappings.','muted'));report.testSummary.unresolvedMappings.forEach(item=>unresolved.appendChild(el('p',item.applicationMethod+' · '+item.status+' · '+item.reason)));
const warnings=section('Warnings');if(!report.warnings.length)warnings.appendChild(el('p','No warnings reported.','muted'));report.warnings.forEach(w=>warnings.appendChild(el('div',w,'warning')));report.errors.forEach(e=>warnings.appendChild(el('div',e,'error')));
if(report.baseline){const baseline=section('Research comparison');baseline.appendChild(el('p','Baseline technique: '+(report.baseline.technique||'Not specified')));baseline.appendChild(el('p','Baseline selected tests: '+report.baseline.selectedTests));baseline.appendChild(el('p','SmartDepTest selected tests: '+report.testSummary.selectedTests));if(report.baseline.selectedTests>0){const reduction=((report.baseline.selectedTests-report.testSummary.selectedTests)/report.baseline.selectedTests)*100;baseline.appendChild(el('p','Reduction: '+(report.baseline.selectedTests-report.testSummary.selectedTests)+' tests ('+reduction.toFixed(1)+'%)'));}else baseline.appendChild(el('p','Percentage reduction is undefined for a zero-test baseline.'));(report.baseline.missedRelevantTests||[]).forEach(x=>baseline.appendChild(el('p','Known missed relevant test: '+x)));(report.baseline.limitations||[]).forEach(x=>baseline.appendChild(el('p',x,'muted')));}
</script>
</body>
</html>`;
}

export function buildStandaloneHtml(report: SmartDepReport): string {
    const summaryRows = [
        ["Available tests", report.testSummary.availableTests],
        ["Unique selected", report.testSummary.selectedTests],
        ["Directly affected", report.testSummary.directlyAffectedTests],
        ["Indirectly affected", report.testSummary.indirectlyAffectedTests],
        ["Not selected", report.testSummary.notSelectedTests],
    ].map(([label, value]) => `<tr><th>${escapeHtml(label)}</th><td>${escapeHtml(value === null ? "Not available" : value)}</td></tr>`).join("");
    const dependencies = report.dependencies.map(d =>
        `<li><strong>${escapeHtml(d.groupId)}:${escapeHtml(d.artifactId)}</strong> ${escapeHtml(d.oldVersion || "(none)")} → ${escapeHtml(d.newVersion || "(none)")} · ${escapeHtml(d.status)}${d.message ? `<p>${escapeHtml(d.message)}</p>` : ""}</li>`).join("");
    const apis = report.changedApis.map(api => {
        const methods = (api.affectedMethods as AffectedMethod[] || []).map(method =>
            `<li>${escapeHtml(method.displayName)} · ${escapeHtml(method.classification)}<p>${escapeHtml(method.reason)}</p>${sourceHtml(method.source)}</li>`).join("");
        return `<li><strong>${escapeHtml(api.className)}.${escapeHtml(api.memberName || "(class)")}</strong> · ${escapeHtml(api.changeType)}<p>Old: ${escapeHtml(api.oldSignature || api.oldDescriptor || "Unavailable")} · New: ${escapeHtml(api.newSignature || api.newDescriptor || "Unavailable")}</p><ul>${methods}</ul></li>`;
    }).join("");
    const affectedMethods = report.affectedMethods.map(method =>
        `<li><strong>${escapeHtml(method.displayName)}</strong> · ${escapeHtml(method.classification)}<p>${escapeHtml(method.reason)}</p>${sourceHtml(method.source)}</li>`).join("");
    const tests = report.tests.map(test =>
        `<article><h3>${escapeHtml(test.testClass)}#${escapeHtml(test.testMethod)} <small>${escapeHtml(test.classification)}</small></h3>${sourceHtml(test.source)}<p>Affected methods:</p><ul>${test.affectedMethods.map(method => `<li>${escapeHtml(method.displayName)} · ${escapeHtml(method.classification)}${sourceHtml(method.source)}</li>`).join("")}</ul><p>Static call paths:</p><ul>${test.callPaths.map(path => `<li>${path.map(escapeHtml).join(" → ")}</li>`).join("") || "<li>Association found; individual call path not retained.</li>"}</ul></article>`).join("");
    const warnings = [...report.warnings, ...report.errors].map(w => `<li>${escapeHtml(w)}</li>`).join("");
    const baseline = report.baseline ? `<section><h2>Research comparison</h2><p>Baseline technique: ${escapeHtml(report.baseline.technique || "Not specified")} · Baseline selected: ${escapeHtml(report.baseline.selectedTests)} · SmartDepTest selected: ${escapeHtml(report.testSummary.selectedTests)}.</p>${report.baseline.selectedTests === 0 ? "<p>Percentage reduction is undefined for a zero-test baseline.</p>" : `<p>Reduction: ${escapeHtml(report.baseline.selectedTests - report.testSummary.selectedTests)} tests (${(((report.baseline.selectedTests - report.testSummary.selectedTests) / report.baseline.selectedTests) * 100).toFixed(1)}%).</p>`}<ul>${(report.baseline.missedRelevantTests || []).map(x => `<li>Known missed relevant test: ${escapeHtml(x)}</li>`).join("")}${(report.baseline.limitations || []).map(x => `<li>${escapeHtml(x)}</li>`).join("")}</ul></section>` : "";
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SmartDepTest Impact Report</title><style>
body{font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;color:#202733;max-width:1050px;margin:32px auto;padding:0 22px}h1{font-size:26px}h2{font-size:19px;border-bottom:1px solid #d5dbe2;padding-bottom:7px;margin-top:30px}h3{font-size:16px}table{border-collapse:collapse;max-width:720px;width:100%}th,td{text-align:left;border-bottom:1px solid #e0e4e8;padding:6px 12px 6px 0}article,li{margin:10px 0}article{padding:12px 0;border-bottom:1px solid #e0e4e8}small,.muted{color:#5a6470}.source{font-family:ui-monospace,monospace;overflow-wrap:anywhere}.warning{background:#fff8e5;border-left:3px solid #b77900;padding:6px 10px}
</style></head><body><h1>SmartDepTest dependency impact report</h1><p class="muted">${escapeHtml(report.projectPath)}</p><p>Analysis status: ${escapeHtml(report.dependencyUpdate.status)} · Revision: ${escapeHtml(report.dependencyUpdate.commitId || "Unavailable")} · Previous: ${escapeHtml(report.dependencyUpdate.previousCommitId || "Unavailable")}</p>
<section><h2>Dependency update</h2><ul>${dependencies || "<li>No dependency updates.</li>"}</ul></section>
<section><h2>Changed APIs and affected application methods</h2><ul>${apis || "<li>No changed API records.</li>"}</ul></section>
<section><h2>Affected application methods (${report.affectedMethods.length})</h2><ul>${affectedMethods || "<li>No affected application methods were identified.</li>"}</ul></section>
<section><h2>Test-selection summary</h2><table>${summaryRows}</table><p>${escapeHtml(report.testSummary.selectionEvidence)}</p></section>
<section><h2>Selected tests (${report.tests.length})</h2>${tests || "<p>No tests were selected.</p>"}</section>
<section><h2>Unresolved mappings</h2><ul>${report.testSummary.unresolvedMappings.map(item => `<li>${escapeHtml(item.applicationMethod)} · ${escapeHtml(item.status)} · ${escapeHtml(item.reason)}</li>`).join("") || "<li>None reported.</li>"}</ul></section>
<section><h2>Warnings and errors</h2><ul>${warnings || "<li>None reported.</li>"}</ul></section>${baseline}
</body></html>`;
}

function sourceHtml(source: SourceLocation | undefined): string {
    if (!source?.path) {
        return `<p class="muted">Source location unavailable.</p>`;
    }
    return `<p class="source">${escapeHtml(source.path)}${source.line ? `:${escapeHtml(source.line)}` : ""}</p>`;
}
