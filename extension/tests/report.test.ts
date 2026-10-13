import assert from "node:assert/strict";
import test from "node:test";
import {
    buildStandaloneHtml,
    buildWebviewHtml,
    escapeHtml,
    SmartDepReport,
    visibleTestCount,
} from "../src/report";

const emptyReport: SmartDepReport = {
    schemaVersion: 1,
    projectPath: "C:\\repo",
    dependencyUpdate: {
        commitId: "",
        previousCommitId: "",
        commitMessage: "",
        status: "ANALYZED",
    },
    dependencies: [],
    changedApis: [],
    affectedMethods: [],
    tests: [],
    testSummary: {
        availableTests: 0,
        selectedTests: 0,
        directlyAffectedTests: 0,
        indirectlyAffectedTests: 0,
        notSelectedTests: 0,
        unresolvedMappings: [],
        selectionEvidence: "",
    },
    warnings: [],
    errors: [],
    baseline: null,
};

test("empty selection produces an explicit standalone report", () => {
    const html = buildStandaloneHtml(emptyReport);
    assert.match(html, /Selected tests \(0\)/);
    assert.match(html, /No tests were selected/);
    assert.match(html, /Available tests/);
});

test("test list initially limits to three and expands to any total", () => {
    assert.equal(visibleTestCount(0, false), 0);
    assert.equal(visibleTestCount(3, false), 3);
    assert.equal(visibleTestCount(400, false), 3);
    assert.equal(visibleTestCount(400, true), 400);
    assert.match(buildWebviewHtml(emptyReport, "nonce"), /Show all /);
    assert.match(buildWebviewHtml(emptyReport, "nonce"), /Show less/);
});

test("standalone report includes every selected test", () => {
    const report: SmartDepReport = {
        ...emptyReport,
        tests: Array.from({ length: 120 }, (_, index) => ({
            id: `sample.Check${index}#test${index}`,
            testClass: `sample.Check${index}`,
            testMethod: `test${index}`,
            classification: index === 0 ? "DIRECT" as const : "INDIRECT" as const,
            source: { path: "", line: null },
            affectedMethods: [],
            callPaths: [],
        })),
        testSummary: {
            ...emptyReport.testSummary,
            availableTests: 120,
            selectedTests: 120,
            directlyAffectedTests: 1,
            indirectlyAffectedTests: 119,
            notSelectedTests: 0,
        },
    };
    const html = buildStandaloneHtml(report);
    assert.match(html, /sample\.Check0#test0/);
    assert.match(html, /sample\.Check119#test119/);
    assert.equal((html.match(/<article>/g) ?? []).length, 120);
});

test("standalone report escapes dynamic HTML content", () => {
    const report = {
        ...emptyReport,
        projectPath: `<img src=x onerror="alert('x')">`,
        warnings: ["<script>alert(1)</script> & 'quoted'"],
    };
    const html = buildStandaloneHtml(report);
    assert.doesNotMatch(html, /<img src=x/);
    assert.doesNotMatch(html, /<script>alert\(1\)<\/script>/);
    assert.match(html, /&lt;img/);
    assert.match(html, /&lt;script&gt;/);
});

test("HTML escaping covers all markup-sensitive characters", () => {
    assert.equal(escapeHtml(`&<>"'`), "&amp;&lt;&gt;&quot;&#39;");
});
