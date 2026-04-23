import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
// Используем .ts-расширение, чтобы модуль резолвился и в Vite, и в Node при прямом запуске скриптов.
import { EXCLUDE_FILES, DIR_LABELS, FILE_LABELS, CODE_SOURCE_DIRS, CODE_EXCLUDED_DIRS, CODE_INCLUDED_EXTENSIONS } from '../config.ts';

// app/src/lib/ → ../../.. → repo root
export const REPO_ROOT = path.resolve(import.meta.dirname, '../../..');
export const DOCS_ROOT = path.join(REPO_ROOT, 'docs');

export interface DocItem {
  path: string;
  label: string;
}

export interface DocSection {
  id: string;
  label: string;
  items: DocItem[];
  readmePath?: string;
}

export interface CodeFile {
  path: string;
  name: string;
  ext: string;
  size: number;
}

export interface CodeDir {
  path: string;
  name: string;
  children: (CodeDir | CodeFile)[];
}

export interface CodeSearchHit {
  lineNumber: number;
  line: string;
}

export interface CodeSearchResult {
  path: string;
  matches: CodeSearchHit[];
  totalMatches: number;
}

export interface Commit {
  hash: string;
  date: string;
  author: string;
  message: string;
  type: string;
  scope: string;
  body: string;
}

// ─── Docs tree ────────────────────────────────────────────────────────────────

export function labelFromFilename(filename: string, dirId?: string): string {
  const base = filename.replace(/\.md$/i, '');
  if (FILE_LABELS[base]) return FILE_LABELS[base];
  const numbered = base.match(/^(\d+)-(.+)$/);
  if (numbered) {
    const num = parseInt(numbered[1], 10);
    const title = numbered[2].replace(/-/g, ' ');
    if (dirId === 'adr') return `ADR-${num} — ${title}`;
    return title.charAt(0).toUpperCase() + title.slice(1);
  }
  return base.replace(/-/g, ' ');
}

export function buildTree(): DocSection[] {
  const sections: DocSection[] = [];
  if (!fs.existsSync(DOCS_ROOT)) return sections;

  const entries = fs.readdirSync(DOCS_ROOT, { withFileTypes: true });
  const dirs = entries.filter(e => e.isDirectory()).sort((a, b) => a.name.localeCompare(b.name));

  for (const dir of dirs) {
    const dirPath = path.join(DOCS_ROOT, dir.name);
    const allFiles = fs.readdirSync(dirPath).filter(f => f.endsWith('.md')).sort();
    const hasReadme = allFiles.includes('README.md');
    const files = allFiles.filter(f => f !== 'README.md' && !EXCLUDE_FILES.includes(f));

    if (files.length === 0 && !hasReadme) continue;

    sections.push({
      id: dir.name,
      label: DIR_LABELS[dir.name] ?? dir.name,
      items: files.map(f => ({
        path: `docs/${dir.name}/${f}`,
        label: labelFromFilename(f, dir.name),
      })),
      readmePath: hasReadme ? `docs/${dir.name}/README.md` : undefined,
    });
  }

  return sections;
}

export function getContent(filePath: string): string | null {
  const abs = path.join(REPO_ROOT, filePath);
  if (!fs.existsSync(abs)) return null;
  return fs.readFileSync(abs, 'utf-8');
}

// ─── Code tree ────────────────────────────────────────────────────────────────

export function buildCodeTree(): CodeDir[] {
  return CODE_SOURCE_DIRS
    .map(relDir => {
      const abs = path.join(REPO_ROOT, relDir);
      if (!fs.existsSync(abs)) return null;
      return buildCodeDir(abs, relDir);
    })
    .filter((d): d is CodeDir => d !== null);
}

function buildCodeDir(absDir: string, relDir: string): CodeDir {
  const entries = fs.readdirSync(absDir, { withFileTypes: true });
  const children: (CodeDir | CodeFile)[] = [];

  for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
    if (entry.name.startsWith('.')) continue;
    if (CODE_EXCLUDED_DIRS.includes(entry.name)) continue;

    const entryAbs = path.join(absDir, entry.name);
    const entryRel = relDir + '/' + entry.name;

    if (entry.isDirectory()) {
      children.push(buildCodeDir(entryAbs, entryRel));
    } else if (entry.isFile()) {
      const ext = path.extname(entry.name).slice(1);
      if (CODE_INCLUDED_EXTENSIONS.includes(ext)) {
        const stat = fs.statSync(entryAbs);
        children.push({ path: entryRel, name: entry.name, ext, size: stat.size });
      }
    }
  }

  return { path: relDir, name: path.basename(relDir), children };
}

export function getCodeContent(filePath: string): string | null {
  const normalized = path.normalize(filePath);
  const allowed = CODE_SOURCE_DIRS.some(dir => normalized.startsWith(dir + '/') || normalized === dir);
  if (!allowed) return null;

  const abs = path.join(REPO_ROOT, normalized);
  if (!fs.existsSync(abs) || !fs.statSync(abs).isFile()) return null;
  try {
    return fs.readFileSync(abs, 'utf-8');
  } catch {
    return null;
  }
}

// ─── Code search ──────────────────────────────────────────────────────────────

const SEARCH_MAX_HITS_PER_FILE = 5;
const SEARCH_LINE_PREVIEW_MAX_LEN = 220;
const SEARCH_MAX_FILES = 200;

export function collectAllCodePaths(): string[] {
  const tree = buildCodeTree();
  const out: string[] = [];
  const walk = (node: CodeDir | CodeFile) => {
    if ('ext' in node) out.push(node.path);
    else node.children.forEach(walk);
  };
  tree.forEach(walk);
  return out;
}

function buildSearchRegex(query: string, isRegex: boolean): RegExp | null {
  try {
    return new RegExp(
      isRegex ? query : query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
      'gi',
    );
  } catch {
    return null;
  }
}

function countLineMatches(line: string, re: RegExp): number {
  re.lastIndex = 0;
  let count = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(line)) !== null) {
    count++;
    if (m[0].length === 0) re.lastIndex++;
  }
  return count;
}

export function searchCodeFiles(query: string, isRegex: boolean): CodeSearchResult[] {
  const re = buildSearchRegex(query, isRegex);
  if (!re) return [];
  const results: CodeSearchResult[] = [];
  for (const filePath of collectAllCodePaths()) {
    const content = getCodeContent(filePath);
    if (!content) continue;
    const lines = content.split('\n');
    const matches: CodeSearchHit[] = [];
    let total = 0;
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const lineHits = countLineMatches(line, re);
      if (lineHits === 0) continue;
      total += lineHits;
      if (matches.length < SEARCH_MAX_HITS_PER_FILE) {
        matches.push({
          lineNumber: i + 1,
          line: line.length > SEARCH_LINE_PREVIEW_MAX_LEN
            ? line.slice(0, SEARCH_LINE_PREVIEW_MAX_LEN) + '…'
            : line,
        });
      }
    }
    if (total > 0) results.push({ path: filePath, matches, totalMatches: total });
    if (results.length >= SEARCH_MAX_FILES) break;
  }
  results.sort((a, b) => b.totalMatches - a.totalMatches);
  return results;
}

// ─── Commits ──────────────────────────────────────────────────────────────────

const COMMIT_TYPE_RE = /^(feat|arch|fix|docs|infra|design|refactor)(?:\(([^)]+)\))?:\s*(.+)$/;

function parseCommit(message: string): { type: string; scope: string; body: string } {
  const m = message.match(COMMIT_TYPE_RE);
  if (m) return { type: m[1], scope: m[2] ?? '', body: m[3] };
  return { type: 'other', scope: '', body: message };
}

export function buildCommits(limit = 60): Commit[] {
  try {
    const raw = execSync(
      `git log --pretty=format:"%H|%ai|%aN|%s" --no-merges -n ${limit}`,
      { cwd: REPO_ROOT, encoding: 'utf-8' }
    );
    return raw.trim().split('\n').filter(Boolean).map(line => {
      const [hash, date, author, ...rest] = line.split('|');
      const message = rest.join('|');
      const { type, scope, body } = parseCommit(message);
      return {
        hash: hash.slice(0, 7),
        date: date.slice(0, 10),
        author,
        message,
        type,
        scope,
        body,
      };
    });
  } catch {
    return [];
  }
}
