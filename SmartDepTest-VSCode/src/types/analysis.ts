export type ExecutionStatus = 'idle' | 'running' | 'success' | 'failed' | 'cancelled';

export interface AnalysisRun {
  id: string;
  sequence: number;
  startedAt: string;
  completedAt?: string;
  status: ExecutionStatus;
  projectPath: string;
  summary: string;
  stdout: string;
  stderr: string;
  report: string;
  impactGraphJson?: string;
  coverageSelectionJson?: string;
  error: string;
  exitCode?: number;
  durationMs?: number;
}

export interface SidebarState {
  history: AnalysisRun[];
  selectedRunId?: string;
  statusText: string;
  projectPath: string;
  canCancel: boolean;
}
