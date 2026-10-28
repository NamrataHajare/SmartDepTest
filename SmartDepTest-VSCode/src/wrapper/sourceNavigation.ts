import * as fs from 'node:fs';
import * as path from 'node:path';

export type SourceNavigationResult =
  | { opened: true; navigatedToLine: boolean }
  | { opened: false; error: string };

export interface SourceNavigationAdapter<TDocument> {
  openDocument: (filePath: string) => Promise<TDocument>;
  showDocument: (document: TDocument, zeroBasedLine?: number) => Promise<void>;
  getLineCount: (document: TDocument) => number;
}

export function resolveSourceFile(projectPath: string, sourcePath: string): string | undefined {
  if (!projectPath || !sourcePath || path.isAbsolute(sourcePath)) {
    return undefined;
  }

  const projectRoot = path.resolve(projectPath);
  const filePath = path.resolve(projectRoot, sourcePath);
  const relativePath = path.relative(projectRoot, filePath);
  if (!relativePath || relativePath === '..' || relativePath.startsWith(`..${path.sep}`) || path.isAbsolute(relativePath)) {
    return undefined;
  }

  try {
    const realRoot = fs.realpathSync(projectRoot);
    const realFile = fs.realpathSync(filePath);
    const realRelativePath = path.relative(realRoot, realFile);
    if (realRelativePath === '..' || realRelativePath.startsWith(`..${path.sep}`) || path.isAbsolute(realRelativePath)) {
      return undefined;
    }
    return fs.statSync(realFile).isFile() ? realFile : undefined;
  } catch {
    return undefined;
  }
}

export async function navigateToSource<TDocument>(
  projectPath: string,
  sourcePath: string,
  lineNumber: unknown,
  adapter: SourceNavigationAdapter<TDocument>,
): Promise<SourceNavigationResult> {
  const filePath = resolveSourceFile(projectPath, sourcePath);
  if (!filePath) {
    return { opened: false, error: 'The source file is unavailable or its path is outside the selected project.' };
  }

  const document = await adapter.openDocument(filePath);
  const lineCount = adapter.getLineCount(document);
  const zeroBasedLine = typeof lineNumber === 'number'
    && Number.isInteger(lineNumber)
    && lineNumber > 0
    && lineNumber <= lineCount
      ? lineNumber - 1
      : undefined;
  await adapter.showDocument(document, zeroBasedLine);
  return { opened: true, navigatedToLine: zeroBasedLine !== undefined };
}
