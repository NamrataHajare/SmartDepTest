"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.getExportContent = getExportContent;
exports.getExportFilename = getExportFilename;
exports.ensureExportExtension = ensureExportExtension;
function getExportContent(run, kind) {
    if (kind === 'impact-graph') {
        return run.impactGraphJson;
    }
    if (kind === 'coverage-selection') {
        return run.coverageSelectionJson;
    }
    const content = run.report || run.stdout || run.stderr || run.error;
    return content.length > 0 ? content : undefined;
}
function getExportFilename(run, kind) {
    const sequence = String(run.sequence).padStart(3, '0');
    if (kind === 'impact-graph') {
        return `smartdeptest-impact-graph-${sequence}.json`;
    }
    if (kind === 'coverage-selection') {
        return `smartdeptest-coverage-selection-${sequence}.json`;
    }
    return `smartdeptest-report-${sequence}.txt`;
}
function ensureExportExtension(filePath, kind) {
    const extension = kind === 'text' ? '.txt' : '.json';
    return filePath.toLowerCase().endsWith(extension) ? filePath : `${filePath}${extension}`;
}
