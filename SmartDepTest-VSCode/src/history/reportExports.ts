import { AnalysisRun } from '../types/analysis';

export type ReportExportKind = 'text' | 'impact-graph' | 'coverage-selection';

export function getExportContent(run: AnalysisRun, kind: ReportExportKind): string | undefined {
  if (kind === 'impact-graph') {
    return run.impactGraphJson;
  }
  if (kind === 'coverage-selection') {
    return run.coverageSelectionJson;
  }

  const content = run.report || run.stdout || run.stderr || run.error;
  return content.length > 0 ? content : undefined;
}

export function getExportFilename(run: AnalysisRun, kind: ReportExportKind): string {
  const sequence = String(run.sequence).padStart(3, '0');
  if (kind === 'impact-graph') {
    return `smartdeptest-impact-graph-${sequence}.json`;
  }
  if (kind === 'coverage-selection') {
    return `smartdeptest-coverage-selection-${sequence}.json`;
  }
  return `smartdeptest-report-${sequence}.txt`;
}

export function ensureExportExtension(filePath: string, kind: ReportExportKind): string {
  const extension = kind === 'text' ? '.txt' : '.json';
  return filePath.toLowerCase().endsWith(extension) ? filePath : `${filePath}${extension}`;
}
