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

// ─── Path helpers ─────────────────────────────────────────────────────────────

const BASE = import.meta.env.BASE_URL;

function dataUrl(relPath: string): string {
  return `${BASE}data/${relPath.replace(/^\/+/, '')}`;
}

function contentUrl(filePath: string): string {
  return dataUrl(`content/${filePath}`);
}

// ─── Dev: local Vite API ──────────────────────────────────────────────────────

async function fetchLocalTree(): Promise<DocSection[]> {
  const res = await fetch('/api/docs/tree');
  const data = await res.json();
  return data.sections;
}

async function fetchLocalContent(filePath: string): Promise<string> {
  const res = await fetch(`/api/docs/content?path=${encodeURIComponent(filePath)}`);
  const data = await res.json();
  if (!res.ok) throw new Error(data.error);
  return data.content;
}

// ─── Prod: статические JSON, сгенерированные на билде ─────────────────────────

async function fetchStaticTree(): Promise<DocSection[]> {
  const res = await fetch(dataUrl('docs-tree.json'));
  if (!res.ok) throw new Error(`docs-tree.json: ${res.status}`);
  const data = await res.json();
  return data.sections as DocSection[];
}

async function fetchStaticContent(filePath: string): Promise<string> {
  const res = await fetch(contentUrl(filePath));
  if (!res.ok) throw new Error(`Not found: ${filePath}`);
  return res.text();
}

// ─── Public API (docs) ───────────────────────────────────────────────────────

export async function getDocTree(): Promise<DocSection[]> {
  if (import.meta.env.DEV) {
    return fetchLocalTree();
  }
  return fetchStaticTree();
}

export async function getDocContent(filePath: string): Promise<string> {
  const raw = import.meta.env.DEV
    ? await fetchLocalContent(filePath)
    : await fetchStaticContent(filePath);
  return rewriteRelativeImagePaths(filePath, raw);
}

// ADR-024: тот же алгоритм применяется build-скриптом к prod-копиям, но в dev сервер
// отдаёт оригинальный markdown, поэтому переписываем пути на лету. В prod копии уже
// абсолютные (`/images/...`) — regex их пропустит, повторного вреда не будет.
const IMG_REGEX = /(!\[[^\]]*\]\()([^)\s]+)(\))/g;

function rewriteRelativeImagePaths(filePath: string, markdown: string): string {
  const relFromDocs = filePath.startsWith('docs/') ? filePath.slice('docs/'.length) : filePath;
  const docDir = relFromDocs.includes('/') ? relFromDocs.slice(0, relFromDocs.lastIndexOf('/')) : '';
  const lines = markdown.split('\n');
  let inFence = false;
  return lines
    .map(line => {
      if (/^\s*```/.test(line)) {
        inFence = !inFence;
        return line;
      }
      if (inFence) return line;
      return line.replace(IMG_REGEX, (match, open: string, url: string, close: string, offset: number) => {
        const before = line.slice(0, offset);
        const backticks = (before.match(/`/g) ?? []).length;
        if (backticks % 2 === 1) return match;
        if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('#') || url.startsWith('/')) {
          return match;
        }
        const resolved = docDir === '' ? url : `${docDir}/${url}`;
        return `${open}/images/${resolved}${close}`;
      });
    })
    .join('\n');
}

// ─── Public API (code) ───────────────────────────────────────────────────────

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

export async function getCodeTree(): Promise<CodeDir[]> {
  if (import.meta.env.DEV) {
    const res = await fetch('/api/code/tree');
    const data = await res.json();
    return data.dirs;
  }
  const res = await fetch(dataUrl('code-tree.json'));
  if (!res.ok) throw new Error(`code-tree.json: ${res.status}`);
  const data = await res.json();
  return data.dirs as CodeDir[];
}

export async function getCodeContent(filePath: string): Promise<{ content: string; ext: string }> {
  if (import.meta.env.DEV) {
    const res = await fetch(`/api/code/content?path=${encodeURIComponent(filePath)}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error);
    return { content: data.content, ext: data.ext };
  }
  const res = await fetch(contentUrl(filePath));
  if (!res.ok) throw new Error(`Not found: ${filePath}`);
  const content = await res.text();
  const ext = filePath.split('.').pop() ?? '';
  return { content, ext };
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

const PROD_SEARCH_MAX_HITS_PER_FILE = 5;
const PROD_SEARCH_LINE_PREVIEW_MAX_LEN = 220;

function collectCodePaths(tree: CodeDir[]): string[] {
  const out: string[] = [];
  const walk = (node: CodeDir | CodeFile) => {
    if ('ext' in node) out.push(node.path);
    else node.children.forEach(walk);
  };
  tree.forEach(walk);
  return out;
}

const prodCodeContentCache = new Map<string, string>();

async function prodFetchAllContents(tree: CodeDir[], onProgress?: () => void): Promise<void> {
  const paths = collectCodePaths(tree).filter(p => !prodCodeContentCache.has(p));
  const BATCH = 8;
  for (let i = 0; i < paths.length; i += BATCH) {
    const batch = paths.slice(i, i + BATCH);
    await Promise.all(batch.map(async p => {
      try {
        const r = await getCodeContent(p);
        prodCodeContentCache.set(p, r.content);
      } catch {
        prodCodeContentCache.set(p, '');
      }
    }));
    onProgress?.();
  }
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

function prodSearchInCache(query: string, isRegex: boolean): CodeSearchResult[] {
  const re = buildSearchRegex(query, isRegex);
  if (!re) return [];
  const results: CodeSearchResult[] = [];
  for (const [filePath, content] of prodCodeContentCache) {
    if (!content) continue;
    const lines = content.split('\n');
    const matches: CodeSearchHit[] = [];
    let total = 0;
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const lineHits = countLineMatches(line, re);
      if (lineHits === 0) continue;
      total += lineHits;
      if (matches.length < PROD_SEARCH_MAX_HITS_PER_FILE) {
        matches.push({
          lineNumber: i + 1,
          line: line.length > PROD_SEARCH_LINE_PREVIEW_MAX_LEN
            ? line.slice(0, PROD_SEARCH_LINE_PREVIEW_MAX_LEN) + '…'
            : line,
        });
      }
    }
    if (total > 0) results.push({ path: filePath, matches, totalMatches: total });
  }
  results.sort((a, b) => b.totalMatches - a.totalMatches);
  return results;
}

export async function searchCode(
  query: string,
  tree: CodeDir[],
  isRegex = false,
  onProgress?: () => void,
): Promise<CodeSearchResult[]> {
  const q = query.trim();
  if (q.length < 2) return [];
  if (import.meta.env.DEV) {
    const url = `/api/code/search?q=${encodeURIComponent(q)}${isRegex ? '&regex=1' : ''}`;
    const res = await fetch(url);
    if (!res.ok) return [];
    const data = await res.json();
    return data.results ?? [];
  }
  await prodFetchAllContents(tree, onProgress);
  return prodSearchInCache(q, isRegex);
}

// ─── Public API (commits) ────────────────────────────────────────────────────

export interface Commit {
  hash: string;
  date: string;
  author: string;
  message: string;
  type: string;
  scope: string;
  body: string;
}

export async function getCommits(limit = 60): Promise<Commit[]> {
  if (import.meta.env.DEV) {
    const res = await fetch(`/api/commits?limit=${limit}`);
    if (!res.ok) return [];
    const data = await res.json();
    return data.commits ?? [];
  }
  const res = await fetch(dataUrl('commits.json'));
  if (!res.ok) return [];
  const data = await res.json() as { commits: Commit[] };
  return data.commits.slice(0, limit);
}

