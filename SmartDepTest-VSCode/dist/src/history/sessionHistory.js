"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SessionHistory = void 0;
class SessionHistory {
    runs = [];
    nextSequence = 1;
    addRun(run) {
        const record = {
            ...run,
            id: `run-${Date.now()}-${this.nextSequence}`,
            sequence: this.nextSequence,
        };
        this.nextSequence += 1;
        this.runs.unshift(record);
        return record;
    }
    updateRun(id, updates) {
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
    getAll() {
        return [...this.runs];
    }
    getById(id) {
        return this.runs.find((run) => run.id === id);
    }
    setStatus(id, status, summary, error) {
        return this.updateRun(id, {
            status,
            summary: summary ?? this.getById(id)?.summary ?? 'No summary available',
            error: error ?? this.getById(id)?.error ?? '',
            completedAt: status === 'running' ? undefined : new Date().toISOString(),
        });
    }
}
exports.SessionHistory = SessionHistory;
