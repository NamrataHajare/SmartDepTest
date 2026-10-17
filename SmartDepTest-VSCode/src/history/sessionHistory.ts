import { AnalysisRun, ExecutionStatus } from '../types/analysis';

export class SessionHistory {
  private readonly runs: AnalysisRun[] = [];
  private nextSequence = 1;

  public addRun(run: Omit<AnalysisRun, 'id' | 'sequence'>): AnalysisRun {
    const record: AnalysisRun = {
      ...run,
      id: `run-${Date.now()}-${this.nextSequence}`,
      sequence: this.nextSequence,
    };
    this.nextSequence += 1;
    this.runs.unshift(record);
    return record;
  }

  public updateRun(id: string, updates: Partial<AnalysisRun>): AnalysisRun | undefined {
    const index = this.runs.findIndex((run) => run.id === id);
    if (index === -1) {
      return undefined;
    }

    this.runs[index] = {
      ...this.runs[index],
      ...updates,
    };
    return this.runs[index];
  }

  public getAll(): AnalysisRun[] {
    return [...this.runs];
  }

  public getById(id: string): AnalysisRun | undefined {
    return this.runs.find((run) => run.id === id);
  }

  public setStatus(id: string, status: ExecutionStatus, summary?: string, error?: string): AnalysisRun | undefined {
    return this.updateRun(id, {
      status,
      summary: summary ?? this.getById(id)?.summary ?? 'No summary available',
      error: error ?? this.getById(id)?.error ?? '',
      completedAt: status === 'running' ? undefined : new Date().toISOString(),
    });
  }
}
