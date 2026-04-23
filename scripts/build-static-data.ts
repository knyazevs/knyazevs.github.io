// Генерация статических JSON + контентных файлов для прод-билда.
// Заменяет runtime-запросы к api.github.com: во время CI-билда мы
// читаем репозиторий с диска и кладём результат в app/public/data/,
// откуда он уходит в app/dist/data/ при сборке Astro.

import fs from 'node:fs';
import path from 'node:path';
import {
  REPO_ROOT,
  DOCS_ROOT,
  buildTree,
  buildCodeTree,
  buildCommits,
  collectAllCodePaths,
  getContent,
  getCodeContent,
} from '../app/src/lib/docs-data.ts';

const DATA_ROOT = path.join(REPO_ROOT, 'app', 'public', 'data');
const CONTENT_ROOT = path.join(DATA_ROOT, 'content');
const IMAGES_ROOT = path.join(REPO_ROOT, 'app', 'public', 'images');
const COMMITS_LIMIT = 200;

function ensureDir(dir: string) {
  fs.mkdirSync(dir, { recursive: true });
}

function writeJson(file: string, data: unknown) {
  ensureDir(path.dirname(file));
  fs.writeFileSync(file, JSON.stringify(data));
}

function writeContentFile(relPath: string, content: string) {
  const abs = path.join(CONTENT_ROOT, relPath);
  ensureDir(path.dirname(abs));
  fs.writeFileSync(abs, content);
}

function resetDataDir() {
  if (fs.existsSync(DATA_ROOT)) {
    fs.rmSync(DATA_ROOT, { recursive: true, force: true });
  }
  ensureDir(DATA_ROOT);
}

function resetImagesDir() {
  if (fs.existsSync(IMAGES_ROOT)) {
    fs.rmSync(IMAGES_ROOT, { recursive: true, force: true });
  }
  ensureDir(IMAGES_ROOT);
}

// ADR-024: относительные пути в markdown-изображениях переписываются на абсолютные
// /images/{docDir}/{file}; сами файлы копируются в app/public/images/ для отдачи статикой.
// Тот же алгоритм применяется на бэкенде в BlockExtractor.resolveUrl — пути в UI-блоках
// согласованы с копиями, лежащими под public/.
// Fenced code-блоки пропускаются: markdown-примеры внутри ``` не считаются за реальные картинки.
const IMAGE_REGEX = /!\[([^\]]*)\]\(([^)\s]+)\)/g;

function processMarkdownImages(docRelPath: string, markdown: string): string {
  // Пути из buildTree() начинаются с `docs/` — снимаем префикс, чтобы URL и target-папка
  // не содержали лишнего `docs/` (напр. profile/profile.png → /images/profile/profile.png).
  const relFromDocs = docRelPath.startsWith('docs/') ? docRelPath.slice('docs/'.length) : docRelPath;
  const docDir = path.dirname(relFromDocs);
  const docDirClean = docDir === '.' ? '' : docDir;

  const lines = markdown.split('\n');
  let inFence = false;
  return lines
    .map((line) => {
      if (/^\s*```/.test(line)) {
        inFence = !inFence;
        return line;
      }
      if (inFence) return line;
      return line.replace(IMAGE_REGEX, (match, alt: string, url: string, offset: number) => {
        // Пропускаем match, если он внутри inline code (нечётное число backticks до него).
        const before = line.slice(0, offset);
        const backticksBefore = (before.match(/`/g) ?? []).length;
        if (backticksBefore % 2 === 1) return match;
        if (
          url.startsWith('http://') ||
          url.startsWith('https://') ||
          url.startsWith('#') ||
          url.startsWith('/')
        ) {
          return match;
        }
        const resolvedRel = path
          .normalize(docDirClean === '' ? url : path.join(docDirClean, url))
          .replace(/\\/g, '/');
        const srcAbs = path.join(DOCS_ROOT, resolvedRel);
        if (!fs.existsSync(srcAbs)) {
          console.warn(
            `[static-data] картинка не найдена: ${path.relative(REPO_ROOT, srcAbs)} (в ${docRelPath})`,
          );
          return match;
        }
        const dstAbs = path.join(IMAGES_ROOT, resolvedRel);
        ensureDir(path.dirname(dstAbs));
        fs.copyFileSync(srcAbs, dstAbs);
        return `![${alt}](/images/${resolvedRel})`;
      });
    })
    .join('\n');
}

function collectAllDocPaths(): string[] {
  const paths: string[] = [];
  for (const section of buildTree()) {
    if (section.readmePath) paths.push(section.readmePath);
    for (const item of section.items) paths.push(item.path);
  }
  return paths;
}

function main() {
  console.log('[static-data] очищаю', path.relative(REPO_ROOT, DATA_ROOT));
  resetDataDir();
  console.log('[static-data] очищаю', path.relative(REPO_ROOT, IMAGES_ROOT));
  resetImagesDir();

  console.log('[static-data] docs-tree.json');
  const tree = buildTree();
  writeJson(path.join(DATA_ROOT, 'docs-tree.json'), { sections: tree });

  console.log('[static-data] code-tree.json');
  const codeTree = buildCodeTree();
  writeJson(path.join(DATA_ROOT, 'code-tree.json'), { dirs: codeTree });

  console.log(`[static-data] commits.json (last ${COMMITS_LIMIT})`);
  const commits = buildCommits(COMMITS_LIMIT);
  writeJson(path.join(DATA_ROOT, 'commits.json'), { commits });

  const docPaths = collectAllDocPaths();
  console.log(`[static-data] docs content: ${docPaths.length} files`);
  for (const p of docPaths) {
    const content = getContent(p);
    if (content === null) {
      console.warn(`[static-data] пропуск: не найден ${p}`);
      continue;
    }
    const processedContent = processMarkdownImages(p, content);
    writeContentFile(p, processedContent);
  }

  const codePaths = collectAllCodePaths();
  console.log(`[static-data] code content: ${codePaths.length} files`);
  for (const p of codePaths) {
    const content = getCodeContent(p);
    if (content === null) {
      console.warn(`[static-data] пропуск: не найден ${p}`);
      continue;
    }
    writeContentFile(p, content);
  }

  console.log('[static-data] готово');
}

main();
