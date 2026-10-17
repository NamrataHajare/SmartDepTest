import test from 'node:test';
import assert from 'node:assert/strict';
import * as path from 'node:path';
import { SessionHistory } from '../history/sessionHistory';
import { validateComponentInstallation } from '../wrapper/smartDepTestRunner';

const componentPath = path.resolve(__dirname, '../../../../SmartDepTest');

test('SessionHistory stores multiple distinct runs without overwriting earlier ones', () => {
  const history = new SessionHistory();

  const first = history.addRun({
    status: 'running',
    summary: 'First run',
    projectPath: 'C:/repo/project-a',
    stdout: '',
    stderr: '',
    report: '',
    error: '',
    startedAt: new Date('2024-01-01T00:00:00Z').toISOString(),
  });

  const second = history.addRun({
    status: 'success',
    summary: 'Second run',
    projectPath: 'C:/repo/project-b',
    stdout: 'done',
    stderr: '',
    report: 'done',
    error: '',
    startedAt: new Date('2024-01-02T00:00:00Z').toISOString(),
  });

  const runs = history.getAll();
  assert.equal(runs.length, 2);
  assert.equal(runs[0].id, second.id);
  assert.equal(runs[1].id, first.id);
  assert.equal(runs[0].summary, 'Second run');
  assert.equal(runs[1].summary, 'First run');
});

test('Component validation succeeds for the existing SmartDepTest project when compiled', () => {
  const result = validateComponentInstallation(componentPath);
  assert.equal(result.isValid, true, result.errors.join('\n'));
  assert.ok(result.classpath.length > 0);
});
