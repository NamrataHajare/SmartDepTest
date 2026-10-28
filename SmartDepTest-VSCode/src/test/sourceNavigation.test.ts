import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { navigateToSource, resolveSourceFile } from '../wrapper/sourceNavigation';

test('resolves project-relative source paths and rejects missing or escaping files', () => {
  const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-source-navigation-'));
  const source = path.join(project, 'src', 'main', 'java', 'demo', 'Service.java');
  fs.mkdirSync(path.dirname(source), { recursive: true });
  fs.writeFileSync(source, 'class Service {}', 'utf8');

  try {
    assert.equal(resolveSourceFile(project, 'src/main/java/demo/Service.java'), source);
    assert.equal(resolveSourceFile(project, '../outside.java'), undefined);
    assert.equal(resolveSourceFile(project, 'missing.java'), undefined);
  } finally {
    fs.rmSync(project, { recursive: true, force: true });
  }
});

test('opens a source document and navigates to a valid one-based line', async () => {
  const project = fs.mkdtempSync(path.join(os.tmpdir(), 'smartdeptest-source-open-'));
  fs.writeFileSync(path.join(project, 'Example.java'), 'one\ntwo\nthree', 'utf8');
  const shownLines: Array<number | undefined> = [];

  try {
    const result = await navigateToSource(project, 'Example.java', 2, {
      openDocument: async (filePath) => ({ filePath }),
      getLineCount: () => 3,
      showDocument: async (_document, line) => { shownLines.push(line); },
    });
    assert.deepEqual(result, { opened: true, navigatedToLine: true });
    assert.equal(shownLines.length, 1);
    assert.equal(shownLines[0], 1);

    const missingLine = await navigateToSource(project, 'Example.java', undefined, {
      openDocument: async (filePath) => ({ filePath }),
      getLineCount: () => 3,
      showDocument: async (_document, line) => { shownLines.push(line); },
    });
    assert.deepEqual(missingLine, { opened: true, navigatedToLine: false });
    assert.deepEqual(shownLines, [1, undefined]);
  } finally {
    fs.rmSync(project, { recursive: true, force: true });
  }
});
