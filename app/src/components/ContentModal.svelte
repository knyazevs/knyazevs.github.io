<script module lang="ts">
  export type ContentTab = 'docs' | 'code' | 'timeline';
</script>

<script lang="ts">
  import { onMount } from 'svelte';
  import { marked, Renderer } from 'marked';
  import hljs from 'highlight.js/lib/core';
  import kotlin from 'highlight.js/lib/languages/kotlin';
  import typescript from 'highlight.js/lib/languages/typescript';
  import javascript from 'highlight.js/lib/languages/javascript';
  import xml from 'highlight.js/lib/languages/xml';
  import json from 'highlight.js/lib/languages/json';
  import yaml from 'highlight.js/lib/languages/yaml';
  import ini from 'highlight.js/lib/languages/ini';
  import bash from 'highlight.js/lib/languages/bash';
  import markdown from 'highlight.js/lib/languages/markdown';
  import css from 'highlight.js/lib/languages/css';
  import {
    getDocTree, getDocContent,
    getCodeTree, getCodeContent,
    getCommits, searchCode,
    type DocSection, type DocItem,
    type CodeDir, type CodeFile,
    type Commit, type CodeSearchResult,
  } from '../lib/docs-client.js';
  import type { ImagePreview } from '../lib/imagePreview.svelte';

  hljs.registerLanguage('kotlin', kotlin);
  hljs.registerLanguage('typescript', typescript);
  hljs.registerLanguage('javascript', javascript);
  hljs.registerLanguage('xml', xml);
  hljs.registerLanguage('json', json);
  hljs.registerLanguage('yaml', yaml);
  hljs.registerLanguage('ini', ini);
  hljs.registerLanguage('bash', bash);
  hljs.registerLanguage('markdown', markdown);
  hljs.registerLanguage('css', css);

  const EXT_TO_LANG: Record<string, string> = {
    kt: 'kotlin', kts: 'kotlin',
    ts: 'typescript', tsx: 'typescript',
    js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
    svelte: 'xml', html: 'xml', xml: 'xml',
    json: 'json',
    yml: 'yaml', yaml: 'yaml',
    toml: 'ini', conf: 'ini', env: 'ini',
    sh: 'bash', bash: 'bash',
    md: 'markdown', mdx: 'markdown',
    css: 'css',
  };

  /** Приоритет для выбора файла «по умолчанию» при открытии кода. Первый найденный выигрывает. */
  const DEFAULT_CODE_FILES = [
    'server/src/main/kotlin/dev/knyazev/Application.kt',
    'server/src/main/kotlin/dev/knyazev/rag/RagPipeline.kt',
    'app/src/pages/index.astro',
  ];

  /** Человеко-читаемые имена корневых директорий (чтобы две «src» не выглядели одинаково). */
  const ROOT_DIR_LABELS: Record<string, string> = {
    'server/src': 'backend',
    'app/src': 'frontend',
    'scripts': 'scripts',
  };

  let { open = false, initialTab = 'docs', openPath = null, imageView = null, onclose }: {
    open?: boolean;
    /** Которая вкладка открывается при монтировании/открытии. */
    initialTab?: ContentTab;
    /** Путь для немедленной навигации. Для docs — относительно docs/, для code — полный (server/src/...). */
    openPath?: string | null;
    /** Если задан — модалка переключается в image-режим: табы скрыты, картинка во весь экран. */
    imageView?: ImagePreview | null;
    onclose?: () => void;
  } = $props();

  let activeTab: ContentTab = $state('docs');
  let fullscreen = $state(false);

  // ─── Docs state ────────────────────────────────────────────────────────────
  type DocView = 'list' | 'detail';
  let docView: DocView = $state('list');
  let sections: DocSection[] = $state([]);
  let docsLoading = $state(true);
  let searchQuery = $state('');
  let activeDoc: DocItem | null = $state(null);
  let docHtml = $state('');
  let docLoading = $state(false);

  // ─── Code state ────────────────────────────────────────────────────────────
  let codeTree: CodeDir[] = $state([]);
  let codeLoading = $state(true);
  /** Путь текущего открытого файла (null если ни один не открыт). Драйвит содержимое + крошки. */
  let selectedCodePath = $state<string | null>(null);
  /** Путь директории, временно подсвеченной в сайдбаре (клик по крошке-директории). */
  let focusedDirPath = $state<string | null>(null);
  let fileContent = $state<string | null>(null);
  let fileExt = $state('');
  let fileLoading = $state(false);
  let expanded = $state<Set<string>>(new Set());
  let codeLoaded = false;

  // ─── Code search state ─────────────────────────────────────────────────────
  type CodeSearchScope = 'file' | 'all';
  let codeSearchQuery = $state('');
  let codeSearchScope = $state<CodeSearchScope>('file');
  let regexMode = $state(false);
  let globalResults = $state<CodeSearchResult[]>([]);
  let globalSearching = $state(false);
  let globalSearchToken = 0;
  let globalSearchTimer: ReturnType<typeof setTimeout> | null = null;
  /** Целевая строка после клика по результату «Везде» — после рендера файла прокручиваем к ней. */
  let pendingMatchLine = $state<number | null>(null);
  let activeMatchIndex = $state(0);
  let codeBodyEl: HTMLElement | null = $state(null);

  // ─── Timeline state ────────────────────────────────────────────────────────
  let commits: Commit[] = $state([]);
  let commitsLoading = $state(false);
  let commitsLoaded = false;

  // ─── Markdown renderer ────────────────────────────────────────────────────
  const renderer = new Renderer();
  renderer.code = ({ text, lang }: { text: string; lang?: string }) => {
    if (lang === 'mermaid') return `<pre class="mermaid">${text}</pre>`;
    const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;');
    return `<pre><code class="language-${lang ?? ''}">${escaped}</code></pre>`;
  };
  marked.use({ renderer });

  // ─── Pending navigation after modal opens ──────────────────────────────────
  let pendingPath = $state<string | null>(null);

  onMount(async () => {
    try {
      sections = (await getDocTree()).sort((a, b) => {
        if (a.id === 'profile') return -1;
        if (b.id === 'profile') return 1;
        if (a.id === 'blog') return -2;
        return a.id.localeCompare(b.id);
      });
    } finally {
      docsLoading = false;
    }
  });

  $effect(() => {
    if (open) {
      activeTab = initialTab;
      pendingPath = openPath ?? null;
      if (initialTab !== 'docs' || !openPath) {
        docView = 'list';
        activeDoc = null;
        docHtml = '';
        searchQuery = '';
      }
    }
  });

  $effect(() => {
    if (open && activeTab === 'code' && !codeLoaded) {
      codeLoaded = true;
      loadCodeTree();
    }
  });

  $effect(() => {
    if (open && activeTab === 'timeline' && !commitsLoaded) {
      commitsLoaded = true;
      loadCommits();
    }
  });

  $effect(() => {
    if (!open || !pendingPath) return;
    if (activeTab === 'docs' && sections.length > 0) {
      const docPath = `docs/${pendingPath}`;
      const found = allDocItems.find(item => item.path === docPath);
      if (found) {
        pendingPath = null;
        openDoc(found);
      }
    } else if (activeTab === 'code' && codeTree.length > 0) {
      const target = pendingPath;
      pendingPath = null;
      selectFile(target);
    }
  });

  // ─── Docs ──────────────────────────────────────────────────────────────────
  const allDocItems = $derived(
    sections.flatMap(s => s.items.map(item => ({ ...item, sectionLabel: s.label, sectionId: s.id })))
  );

  const activeDocSection = $derived(
    activeDoc ? sections.find(s => s.items.some(i => i.path === activeDoc!.path)) ?? null : null
  );

  let filteredDocItems = $derived(
    searchQuery.trim().length < 2
      ? allDocItems
      : allDocItems.filter(item =>
          item.label.toLowerCase().includes(searchQuery.toLowerCase()) ||
          item.path.toLowerCase().includes(searchQuery.toLowerCase())
        )
  );

  async function openDoc(item: DocItem) {
    activeDoc = item;
    docHtml = '';
    docLoading = true;
    docView = 'detail';
    try {
      const md = await getDocContent(item.path);
      docHtml = await marked(md) as string;
    } catch {
      docHtml = '<p class="doc-error">Не удалось загрузить документ.</p>';
    } finally {
      docLoading = false;
    }
  }

  function backToDocsList() {
    docView = 'list';
    activeDoc = null;
    docHtml = '';
  }

  function iconForSection(id: string): string {
    const icons: Record<string, string> = {
      adr: '⬡', blog: '✎', profile: '◉', experience: '◈',
      projects: '◆', skills: '◇', diagrams: '⬡',
    };
    return icons[id] ?? '○';
  }

  function excerptFromPath(path: string): string {
    return path.replace(/\.md$/, '').split('/').join(' › ');
  }

  // ─── Code ──────────────────────────────────────────────────────────────────
  type CodeNode = CodeDir | CodeFile;

  function isFile(node: CodeNode): node is CodeFile {
    return 'ext' in node;
  }

  async function loadCodeTree() {
    codeLoading = true;
    try {
      codeTree = await getCodeTree();
      // Раскрываем корневые директории по умолчанию
      const next = new Set(expanded);
      for (const dir of codeTree) next.add(dir.path);
      expanded = next;
    } finally {
      codeLoading = false;
    }
    // Если файл ещё не выбран — открываем main по умолчанию.
    if (!selectedCodePath && !pendingPath) {
      const defaultPath = pickDefaultFile();
      if (defaultPath) selectFile(defaultPath);
    }
  }

  /** Найти путь первого существующего файла из DEFAULT_CODE_FILES; иначе — первый файл в дереве. */
  function pickDefaultFile(): string | null {
    const allPaths = new Set<string>();
    const stack: CodeNode[] = [...codeTree];
    let firstFile: string | null = null;
    while (stack.length) {
      const n = stack.pop()!;
      if (isFile(n)) {
        allPaths.add(n.path);
        if (!firstFile) firstFile = n.path;
      } else {
        stack.push(...n.children);
      }
    }
    for (const p of DEFAULT_CODE_FILES) {
      if (allPaths.has(p)) return p;
    }
    return firstFile;
  }

  function displayName(node: CodeNode): string {
    return ROOT_DIR_LABELS[node.path] ?? node.name;
  }

  function sortNodes(a: CodeNode, b: CodeNode): number {
    const aDir = !isFile(a);
    const bDir = !isFile(b);
    if (aDir !== bDir) return aDir ? -1 : 1;
    return displayName(a).localeCompare(displayName(b));
  }

  function toggleDir(path: string) {
    const next = new Set(expanded);
    if (next.has(path)) next.delete(path);
    else next.add(path);
    expanded = next;
  }

  /** Раскрыть цепочку директорий до указанного пути. Если includeSelf — раскрыть и саму директорию. */
  function expandAncestors(path: string, includeSelf: boolean) {
    const next = new Set(expanded);
    const parts = path.split('/');
    const last = includeSelf ? parts.length : parts.length - 1;
    for (let i = 1; i <= last; i++) next.add(parts.slice(0, i).join('/'));
    expanded = next;
  }

  async function selectFile(path: string) {
    selectedCodePath = path;
    focusedDirPath = null;
    fileContent = null;
    expandAncestors(path, false);
    fileLoading = true;
    try {
      const result = await getCodeContent(path);
      fileContent = result.content;
      fileExt = result.ext;
    } catch {
      fileContent = '// Не удалось загрузить файл';
    } finally {
      fileLoading = false;
    }
  }

  /** Клик по хлебной крошке-директории: раскрыть сайдбар до этого пути и подсветить. */
  function navigateToDir(path: string) {
    focusedDirPath = path || null;
    if (path) expandAncestors(path, true);
  }

  /** Подсветка синтаксиса через highlight.js (возвращает готовый HTML). */
  function highlightCode(content: string, ext: string): string {
    const lang = EXT_TO_LANG[ext.toLowerCase()];
    if (lang) {
      try {
        return hljs.highlight(content, { language: lang, ignoreIllegals: true }).value;
      } catch {
        /* fall through */
      }
    }
    return content
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function escapeRegex(s: string): string {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  function buildClientRegex(query: string, isRegex: boolean): RegExp | null {
    if (!query || query.length < 2) return null;
    try {
      return new RegExp(isRegex ? query : escapeRegex(query), 'gi');
    } catch {
      return null;
    }
  }

  /**
   * Оборачивает совпадения в <mark>, не трогая HTML-теги подсветки.
   * Делит вход на чередующиеся фрагменты «тег / текст»; регексп применяется только к тексту.
   */
  function wrapMatchesInHtml(html: string, re: RegExp | null): string {
    if (!re) return html;
    return html.replace(/(<[^>]*>)|([^<]+)/g, (_m, tag, text) => {
      if (tag) return tag;
      re.lastIndex = 0;
      return (text as string).replace(re, (hit: string) =>
        hit ? `<mark>${hit}</mark>` : hit,
      );
    });
  }

  /** Позиции каждого совпадения в исходном тексте: для навигации по файлу. */
  function findMatchPositions(content: string, re: RegExp | null): Array<{ lineNumber: number }> {
    if (!re) return [];
    const lineStarts: number[] = [0];
    for (let i = 0; i < content.length; i++) {
      if (content[i] === '\n') lineStarts.push(i + 1);
    }
    const positions: Array<{ lineNumber: number }> = [];
    re.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = re.exec(content)) !== null) {
      let lo = 0, hi = lineStarts.length - 1;
      while (lo < hi) {
        const mid = (lo + hi + 1) >> 1;
        if (lineStarts[mid] <= m.index) lo = mid;
        else hi = mid - 1;
      }
      positions.push({ lineNumber: lo + 1 });
      if (m[0].length === 0) re.lastIndex++;
      if (positions.length > 5000) break;
    }
    return positions;
  }

  /** Скомпилированный regex для подсветки в текущем файле. */
  const inFileRegex = $derived(
    codeSearchScope === 'file' ? buildClientRegex(codeSearchQuery.trim(), regexMode) : null
  );

  /** Невалидный regex в режиме regex (для красной рамки в инпуте). */
  const regexInvalid = $derived(
    regexMode && codeSearchQuery.trim().length >= 2 && (() => {
      try { new RegExp(codeSearchQuery); return false; } catch { return true; }
    })()
  );

  /** Готовый к рендеру HTML текущего файла: hljs-подсветка + <mark> для совпадений. */
  const renderedFileHtml = $derived(
    selectedCodePath !== null && fileContent !== null
      ? wrapMatchesInHtml(highlightCode(fileContent, fileExt), inFileRegex)
      : ''
  );

  const matchPositions = $derived(
    codeSearchScope === 'file' && fileContent !== null
      ? findMatchPositions(fileContent, inFileRegex)
      : []
  );

  $effect(() => {
    // Клампим индекс активного совпадения, когда список совпадений меняется.
    if (matchPositions.length === 0) {
      if (activeMatchIndex !== 0) activeMatchIndex = 0;
    } else if (activeMatchIndex >= matchPositions.length) {
      activeMatchIndex = matchPositions.length - 1;
    }
  });

  function scrollToActiveMatch() {
    if (!codeBodyEl) return;
    const marks = codeBodyEl.querySelectorAll('mark');
    const target = marks[activeMatchIndex];
    if (target) (target as HTMLElement).scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  function nextMatch() {
    if (matchPositions.length === 0) return;
    activeMatchIndex = (activeMatchIndex + 1) % matchPositions.length;
    scrollToActiveMatch();
  }

  function prevMatch() {
    if (matchPositions.length === 0) return;
    activeMatchIndex = (activeMatchIndex - 1 + matchPositions.length) % matchPositions.length;
    scrollToActiveMatch();
  }

  $effect(() => {
    // Маркируем активный <mark> CSS-классом — чтобы выделить его жирно.
    if (!codeBodyEl) return;
    const _depHtml = renderedFileHtml; // явная зависимость, чтобы пересчёт шёл после рендера
    const _depIdx = activeMatchIndex;
    queueMicrotask(() => {
      if (!codeBodyEl) return;
      const marks = codeBodyEl.querySelectorAll('mark');
      marks.forEach(m => m.classList.remove('active'));
      const target = marks[activeMatchIndex];
      if (target) target.classList.add('active');
    });
  });

  function clearCodeSearch() {
    codeSearchQuery = '';
    globalResults = [];
    globalSearching = false;
    globalSearchToken++;
    activeMatchIndex = 0;
  }

  function pluralFile(n: number): string {
    const lastTwo = n % 100;
    if (lastTwo >= 11 && lastTwo <= 14) return 'файлов';
    const last = n % 10;
    if (last === 1) return 'файл';
    if (last >= 2 && last <= 4) return 'файла';
    return 'файлов';
  }

  function onSearchInputKeydown(e: KeyboardEvent) {
    if (e.key === 'Enter' && codeSearchScope === 'file' && matchPositions.length > 0) {
      e.preventDefault();
      if (e.shiftKey) prevMatch(); else nextMatch();
    }
  }

  $effect(() => {
    // Глобальный поиск с дебаунсом (запрос, скоуп, regex-режим, готовность дерева).
    const q = codeSearchQuery.trim();
    const isRegex = regexMode;
    if (codeSearchScope !== 'all') return;
    if (q.length < 2) {
      globalResults = [];
      globalSearching = false;
      return;
    }
    if (codeTree.length === 0) return;

    if (globalSearchTimer) clearTimeout(globalSearchTimer);
    globalSearching = true;
    const myToken = ++globalSearchToken;
    globalSearchTimer = setTimeout(async () => {
      try {
        const res = await searchCode(q, codeTree, isRegex);
        if (myToken !== globalSearchToken) return;
        globalResults = res;
      } finally {
        if (myToken === globalSearchToken) globalSearching = false;
      }
    }, 250);
  });

  function openSearchResult(res: CodeSearchResult, hit?: { lineNumber: number }) {
    pendingMatchLine = hit?.lineNumber ?? res.matches[0]?.lineNumber ?? 1;
    codeSearchScope = 'file';
    selectFile(res.path);
  }

  $effect(() => {
    // После загрузки файла позиционируем activeMatchIndex на запрошенную строку и прокручиваем.
    if (pendingMatchLine === null) return;
    if (fileLoading || fileContent === null || !codeBodyEl) return;
    const positions = matchPositions;
    if (positions.length === 0) {
      pendingMatchLine = null;
      return;
    }
    const target = pendingMatchLine;
    let idx = positions.findIndex(p => p.lineNumber >= target);
    if (idx === -1) idx = positions.length - 1;
    activeMatchIndex = idx;
    pendingMatchLine = null;
    queueMicrotask(scrollToActiveMatch);
  });

  /** Хлебные крошки от корня до открытого файла. Если файл не открыт — только «Корень». */
  const crumbs = $derived<Array<{ label: string; path: string; isFile: boolean }>>([
    { label: 'Корень', path: '', isFile: false },
    ...(selectedCodePath
      ? selectedCodePath.split('/').map((seg, i, arr) => ({
          label: seg,
          path: arr.slice(0, i + 1).join('/'),
          isFile: i === arr.length - 1,
        }))
      : []),
  ]);

  function lineCount(content: string): number {
    return content.split('\n').length;
  }

  function fileIcon(ext: string): string {
    const icons: Record<string, string> = {
      kt: 'K', kts: 'K', ts: 'T', js: 'J', mjs: 'J',
      svelte: 'S', toml: '⚙', yml: '⚙', yaml: '⚙',
      json: '{}', md: '≡', conf: '⚙', env: '$',
    };
    return icons[ext] ?? '·';
  }

  // ─── Timeline ──────────────────────────────────────────────────────────────
  const TYPE_LABEL: Record<string, string> = {
    feat: 'Feature', arch: 'Architecture', fix: 'Fix',
    docs: 'Docs', infra: 'Infra', design: 'Design', refactor: 'Refactor', other: 'Commit',
  };

  async function loadCommits() {
    commitsLoading = true;
    try {
      commits = await getCommits(60);
    } finally {
      commitsLoading = false;
    }
  }

  const commitsByDay = $derived.by(() => {
    const map = new Map<string, Commit[]>();
    for (const c of commits) {
      if (!map.has(c.date)) map.set(c.date, []);
      map.get(c.date)!.push(c);
    }
    return Array.from(map.entries());
  });

  function pluralCommits(n: number): string {
    if (n === 1) return 'коммит';
    if (n < 5) return 'коммита';
    return 'коммитов';
  }

  // ─── Modal shell ───────────────────────────────────────────────────────────
  function handleOverlayClick(e: MouseEvent) {
    if (e.target === e.currentTarget) onclose?.();
  }

  $effect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onclose?.();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  function switchTab(tab: ContentTab) {
    activeTab = tab;
  }
</script>

{#if open && imageView}
  <!-- ─── Image fullscreen view ─────────────────────────────────────────────
       Отдельная ветка без табов: клик по картинке в чате раскрывает её
       на весь экран. Оверлей закрывает по клику вне картинки и по Esc
       (общий handler выше). -->
  <div
    class="overlay overlay-image"
    role="dialog"
    aria-modal="true"
    aria-label="Просмотр изображения"
    onclick={() => onclose?.()}
  >
    <button
      class="close-btn image-close"
      onclick={(e) => { e.stopPropagation(); onclose?.(); }}
      aria-label="Закрыть"
      title="Закрыть"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
        <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
      </svg>
    </button>
    <!-- svelte-ignore a11y_click_events_have_key_events -->
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <figure class="image-fullscreen" onclick={(e) => e.stopPropagation()}>
      <img src={imageView.url} alt={imageView.alt ?? imageView.caption ?? ''} />
      {#if imageView.caption}
        <figcaption>{imageView.caption}</figcaption>
      {/if}
    </figure>
  </div>
{:else if open}
  <div
    class="overlay"
    role="dialog"
    aria-modal="true"
    aria-label="Содержимое проекта"
    onclick={handleOverlayClick}
  >
    <div class="modal" class:fullscreen data-tab={activeTab}>

      <!-- ─── Header ───────────────────────────────────── -->
      <div class="modal-header">
        <div class="tabs" role="tablist">
          <button
            class="tab"
            class:active={activeTab === 'docs'}
            role="tab"
            aria-selected={activeTab === 'docs'}
            onclick={() => switchTab('docs')}
          >Документация</button>
          <button
            class="tab"
            class:active={activeTab === 'code'}
            role="tab"
            aria-selected={activeTab === 'code'}
            onclick={() => switchTab('code')}
          >Код</button>
          <button
            class="tab"
            class:active={activeTab === 'timeline'}
            role="tab"
            aria-selected={activeTab === 'timeline'}
            onclick={() => switchTab('timeline')}
          >История</button>
        </div>

        <button
          class="close-btn"
          onclick={() => fullscreen = !fullscreen}
          aria-label={fullscreen ? 'Свернуть' : 'Во весь экран'}
          title={fullscreen ? 'Свернуть' : 'Во весь экран'}
        >
          {#if fullscreen}
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="4 14 10 14 10 20"/>
              <polyline points="20 10 14 10 14 4"/>
              <line x1="14" y1="10" x2="21" y2="3"/>
              <line x1="3" y1="21" x2="10" y2="14"/>
            </svg>
          {:else}
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="15 3 21 3 21 9"/>
              <polyline points="9 21 3 21 3 15"/>
              <line x1="21" y1="3" x2="14" y2="10"/>
              <line x1="3" y1="21" x2="10" y2="14"/>
            </svg>
          {/if}
        </button>
        <button class="close-btn" onclick={() => onclose?.()} aria-label="Закрыть" title="Закрыть">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <!-- ─── Docs tab ───────────────────────────────────── -->
      {#if activeTab === 'docs'}
        {#if docView === 'list'}
          <div class="search-bar">
            <svg class="search-icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            <input
              class="search-input"
              type="text"
              placeholder="Поиск в документации…"
              bind:value={searchQuery}
              autocomplete="off"
              spellcheck="false"
            />
            {#if searchQuery}
              <button class="search-clear" onclick={() => searchQuery = ''} aria-label="Очистить">
                <svg width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5">
                  <line x1="12" y1="4" x2="4" y2="12"/><line x1="4" y1="4" x2="12" y2="12"/>
                </svg>
              </button>
            {/if}
          </div>

          <div class="doc-list">
            {#if docsLoading}
              <div class="list-hint">Загрузка…</div>
            {:else if filteredDocItems.length === 0}
              <div class="list-hint">Ничего не найдено</div>
            {:else if searchQuery.trim().length >= 2}
              {#each filteredDocItems as item}
                <button class="result-item" onclick={() => openDoc(item)}>
                  <div class="result-path">{excerptFromPath(item.path)}</div>
                  <div class="result-label">{item.label}</div>
                </button>
              {/each}
            {:else}
              {#each sections as section}
                {#if section.items.length > 0}
                  <div class="section-group">
                    <div class="section-header">
                      <span class="section-icon">{iconForSection(section.id)}</span>
                      <span>{section.label}</span>
                    </div>
                    {#each section.items as item}
                      <button class="result-item indented" onclick={() => openDoc(item)}>
                        <div class="result-label">{item.label}</div>
                        <div class="result-path">{item.path}</div>
                      </button>
                    {/each}
                  </div>
                {/if}
              {/each}
            {/if}
          </div>
        {:else}
          <div class="doc-detail">
            {#if activeDoc}
              <nav class="crumbs doc-crumbs" aria-label="Путь документа">
                <button class="crumb" onclick={backToDocsList}>Документация</button>
                {#if activeDocSection}
                  <span class="crumb-sep">›</span>
                  <span class="crumb current">{activeDocSection.label}</span>
                {/if}
                <span class="crumb-sep">›</span>
                <span class="crumb current">{activeDoc.label}</span>
              </nav>
            {/if}
            {#if docLoading}
              <div class="list-hint">Загрузка…</div>
            {:else}
              <article class="doc-content" aria-label={activeDoc?.label ?? 'Документ'}>
                {@html docHtml}
              </article>
            {/if}
          </div>
        {/if}

      <!-- ─── Code tab ───────────────────────────────────── -->
      {:else if activeTab === 'code'}
        <div class="code-pane">
          <aside class="code-sidebar" aria-label="Дерево файлов">
            {#if codeLoading}
              <div class="list-hint">Загрузка…</div>
            {:else}
              {@render nodeTree(codeTree, 0)}
            {/if}
          </aside>

          <div class="code-main">
            <nav class="crumbs" aria-label="Путь к файлу">
              {#each crumbs as crumb, i}
                {#if i > 0}<span class="crumb-sep">›</span>{/if}
                {#if crumb.isFile || (i === crumbs.length - 1 && !selectedCodePath)}
                  <span class="crumb current">{crumb.label}</span>
                {:else}
                  <button class="crumb" onclick={() => navigateToDir(crumb.path)}>{crumb.label}</button>
                {/if}
              {/each}
              {#if selectedCodePath && fileContent !== null}
                <span class="crumb-meta">{lineCount(fileContent)} строк · {fileExt}</span>
              {/if}
            </nav>

            <div class="code-search">
              <svg class="search-icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
              </svg>
              <input
                class="search-input"
                class:invalid={regexInvalid}
                type="text"
                placeholder={codeSearchScope === 'file' ? 'Поиск в файле…' : 'Поиск во всех файлах…'}
                bind:value={codeSearchQuery}
                onkeydown={onSearchInputKeydown}
                autocomplete="off"
                spellcheck="false"
              />
              <button
                class="icon-btn regex-btn"
                class:active={regexMode}
                onclick={() => regexMode = !regexMode}
                aria-pressed={regexMode}
                aria-label="Регулярное выражение"
                title="Регулярное выражение"
              >.*</button>
              {#if codeSearchQuery.trim().length >= 2}
                {#if codeSearchScope === 'file'}
                  <span class="search-meta">
                    {#if regexInvalid}
                      <span class="search-error">неверный regex</span>
                    {:else if matchPositions.length === 0}
                      нет совпадений
                    {:else}
                      {activeMatchIndex + 1} / {matchPositions.length}
                    {/if}
                  </span>
                  <div class="nav-arrows">
                    <button
                      class="icon-btn"
                      onclick={prevMatch}
                      disabled={matchPositions.length === 0}
                      aria-label="Предыдущее совпадение"
                      title="Предыдущее (Shift+Enter)"
                    >
                      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="18 15 12 9 6 15"/></svg>
                    </button>
                    <button
                      class="icon-btn"
                      onclick={nextMatch}
                      disabled={matchPositions.length === 0}
                      aria-label="Следующее совпадение"
                      title="Следующее (Enter)"
                    >
                      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="6 9 12 15 18 9"/></svg>
                    </button>
                  </div>
                {:else}
                  <span class="search-meta">
                    {#if regexInvalid}
                      <span class="search-error">неверный regex</span>
                    {:else if globalSearching}
                      Поиск…
                    {:else}
                      {globalResults.length} {pluralFile(globalResults.length)}
                    {/if}
                  </span>
                {/if}
              {/if}
              {#if codeSearchQuery}
                <button class="icon-btn" onclick={clearCodeSearch} aria-label="Очистить" title="Очистить">
                  <svg width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5">
                    <line x1="12" y1="4" x2="4" y2="12"/><line x1="4" y1="4" x2="12" y2="12"/>
                  </svg>
                </button>
              {/if}
              <div class="scope-toggle" role="tablist" aria-label="Область поиска">
                <button
                  class="scope-btn"
                  class:active={codeSearchScope === 'file'}
                  role="tab"
                  aria-selected={codeSearchScope === 'file'}
                  onclick={() => codeSearchScope = 'file'}
                >В файле</button>
                <button
                  class="scope-btn"
                  class:active={codeSearchScope === 'all'}
                  role="tab"
                  aria-selected={codeSearchScope === 'all'}
                  onclick={() => codeSearchScope = 'all'}
                >Везде</button>
              </div>
            </div>

            <div class="code-body" bind:this={codeBodyEl}>
              {#if codeSearchScope === 'all' && codeSearchQuery.trim().length >= 2}
                {#if globalSearching}
                  <div class="list-hint">Поиск во всех файлах…</div>
                {:else if globalResults.length === 0}
                  <div class="list-hint">Ничего не найдено</div>
                {:else}
                  <div class="search-results">
                    {#each globalResults as res}
                      <div class="search-file">
                        <button class="search-file-header" onclick={() => openSearchResult(res)}>
                          <span class="search-file-path">{res.path}</span>
                          <span class="search-file-count">{res.totalMatches}</span>
                        </button>
                        <div class="search-file-hits">
                          {#each res.matches as hit}
                            <button class="search-hit" onclick={() => openSearchResult(res, hit)}>
                              <span class="search-hit-line">{hit.lineNumber}</span>
                              <span class="search-hit-text">{hit.line}</span>
                            </button>
                          {/each}
                          {#if res.totalMatches > res.matches.length}
                            <div class="search-hit-more">+ ещё {res.totalMatches - res.matches.length}</div>
                          {/if}
                        </div>
                      </div>
                    {/each}
                  </div>
                {/if}
              {:else if fileLoading}
                <div class="list-hint">Загрузка…</div>
              {:else if selectedCodePath && fileContent !== null}
                <pre class="code-view"><code class="hljs">{@html renderedFileHtml}</code></pre>
              {:else}
                <div class="list-hint">Выберите файл в дереве слева</div>
              {/if}
            </div>
          </div>
        </div>

        {#snippet nodeTree(nodes: CodeNode[], level: number)}
          {#each [...nodes].sort(sortNodes) as node}
            {#if isFile(node)}
              <button
                class="tree-entry file"
                class:active={selectedCodePath === node.path}
                style:padding-left="{10 + level * 14}px"
                onclick={() => selectFile(node.path)}
              >
                <span class="tree-icon">{fileIcon(node.ext)}</span>
                <span class="tree-name">{displayName(node)}</span>
              </button>
            {:else}
              <button
                class="tree-entry dir"
                class:focused={focusedDirPath === node.path}
                style:padding-left="{10 + level * 14}px"
                onclick={() => toggleDir(node.path)}
              >
                <span class="tree-chev">{expanded.has(node.path) ? '▾' : '▸'}</span>
                <span class="tree-name">{displayName(node)}</span>
              </button>
              {#if expanded.has(node.path)}
                {@render nodeTree(node.children, level + 1)}
              {/if}
            {/if}
          {/each}
        {/snippet}

      <!-- ─── Timeline tab ───────────────────────────────── -->
      {:else}
        <div class="timeline-pane">
          {#if commitsLoading}
            <div class="list-hint">Загрузка…</div>
          {:else if commits.length === 0}
            <div class="list-hint">История недоступна</div>
          {:else}
            <div class="timeline-sub">Git log — {commits.length} коммитов</div>
            <div class="timeline">
              {#each commitsByDay as [date, day]}
                <div class="day-group">
                  <div class="day-marker">
                    <span class="day-date">{date}</span>
                    <span class="day-count">{day.length} {pluralCommits(day.length)}</span>
                  </div>
                  <div class="day-commits">
                    {#each day as c}
                      <div class="commit" data-type={c.type}>
                        <div class="commit-dot"></div>
                        <div class="commit-body-wrap">
                          <div class="commit-top">
                            {#if c.scope}<span class="commit-scope">{c.scope}</span>{/if}
                            <span class="commit-body">{c.body}</span>
                            <span class="commit-hash">{c.hash}</span>
                          </div>
                          <span class="commit-type">{TYPE_LABEL[c.type] ?? c.type}</span>
                        </div>
                      </div>
                    {/each}
                  </div>
                </div>
              {/each}
            </div>
          {/if}
        </div>
      {/if}

    </div>
  </div>
{/if}

<style>
  /* ─── Overlay ──────────────────────────────────── */

  .overlay {
    position: fixed;
    inset: 0;
    z-index: 500;
    background: var(--color-overlay-bg);
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;
    outline: none;
    animation: fadeOverlay 0.15s ease;
  }

  /* ─── Image fullscreen view ────────────────────── */

  .overlay-image {
    padding: 0;
    background: rgba(0, 0, 0, 0.88);
  }

  .image-fullscreen {
    margin: 0;
    max-width: 95vw;
    max-height: 95vh;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 10px;
    animation: slideUp 0.18s ease;
  }

  .image-fullscreen img {
    display: block;
    max-width: 95vw;
    max-height: 90vh;
    width: auto;
    height: auto;
    object-fit: contain;
    border-radius: 6px;
    box-shadow: 0 24px 64px rgba(0, 0, 0, 0.6);
  }

  .image-fullscreen figcaption {
    max-width: 80vw;
    font-size: 13px;
    color: rgba(255, 255, 255, 0.75);
    text-align: center;
    line-height: 1.4;
  }

  .image-close {
    position: fixed;
    top: 16px;
    right: 16px;
    z-index: 1;
    background: rgba(0, 0, 0, 0.4);
  }

  @keyframes fadeOverlay {
    from { opacity: 0; }
    to   { opacity: 1; }
  }

  /* ─── Modal ────────────────────────────────────── */

  .modal {
    width: 100%;
    max-width: 900px;
    height: 85vh;
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: 14px;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    animation: slideUp 0.18s ease;
    box-shadow: 0 24px 64px rgba(0, 0, 0, 0.5);
    transition: max-width 0.18s ease, height 0.18s ease, border-radius 0.18s ease;
  }

  .modal.fullscreen {
    max-width: none;
    height: 100%;
    border-radius: 0;
  }

  .overlay:has(.modal.fullscreen) {
    padding: 0;
  }

  @keyframes slideUp {
    from { opacity: 0; transform: translateY(8px); }
    to   { opacity: 1; transform: translateY(0); }
  }

  /* ─── Header with tabs ─────────────────────────── */

  .modal-header {
    display: flex;
    align-items: center;
    padding: 10px 12px;
    border-bottom: 1px solid var(--color-border);
    gap: 8px;
    flex-shrink: 0;
  }

  .tabs {
    display: flex;
    gap: 4px;
    flex: 1;
    justify-content: center;
  }

  .tab {
    background: none;
    border: none;
    padding: 8px 14px 10px;
    font-size: 13px;
    font-family: var(--font-sans);
    color: var(--color-text-dim);
    cursor: pointer;
    position: relative;
    transition: color var(--transition);
    border-bottom: 2px solid transparent;
    margin-bottom: -1px;
  }

  .tab:hover {
    color: var(--color-text);
  }

  .tab.active {
    color: var(--color-text-heading);
    border-bottom-color: var(--color-accent);
  }

  .close-btn {
    width: 28px;
    height: 28px;
    border-radius: 7px;
    border: 1px solid var(--color-border);
    background: none;
    color: var(--color-text-dim);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    flex-shrink: 0;
    transition: color var(--transition), border-color var(--transition);
  }

  .close-btn:hover {
    color: var(--color-text-heading);
    border-color: var(--color-text-dim);
  }

  /* ─── Search (docs) ────────────────────────────── */

  .search-bar {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
  }

  .search-icon {
    color: var(--color-text-dim);
    flex-shrink: 0;
  }

  .search-input {
    flex: 1;
    background: none;
    border: none;
    outline: none;
    font-size: 13px;
    font-family: var(--font-mono);
    color: var(--color-text);
    caret-color: var(--color-accent);
  }

  .search-input::placeholder { color: var(--color-text-dim); }

  .search-clear {
    background: none;
    border: none;
    color: var(--color-text-dim);
    cursor: pointer;
    padding: 2px;
    display: flex;
    align-items: center;
    border-radius: 3px;
    transition: color var(--transition);
  }

  .search-clear:hover { color: var(--color-text); }

  /* ─── Doc list ─────────────────────────────────── */

  .doc-list {
    flex: 1;
    overflow-y: auto;
    padding: 6px 0;
  }

  .list-hint {
    padding: 20px;
    text-align: center;
    font-size: 12px;
    color: var(--color-text-dim);
    font-family: var(--font-mono);
  }

  .section-group {
    margin-bottom: 4px;
  }

  .section-header {
    display: flex;
    align-items: center;
    gap: 7px;
    padding: 8px 16px 4px;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.10em;
    text-transform: uppercase;
    color: var(--color-text-dim);
    font-family: var(--font-mono);
    user-select: none;
  }

  .section-icon {
    font-size: 12px;
    opacity: 0.6;
  }

  .result-item {
    display: flex;
    flex-direction: column;
    gap: 2px;
    width: 100%;
    text-align: left;
    padding: 7px 16px;
    background: none;
    border: none;
    cursor: pointer;
    transition: background var(--transition);
    border-left: 2px solid transparent;
  }

  .result-item.indented {
    padding-left: 32px;
  }

  .result-item:hover {
    background: var(--color-surface-hover);
    border-left-color: var(--color-accent);
  }

  .result-label {
    font-size: 13px;
    color: var(--color-text);
    font-family: var(--font-sans);
  }

  .result-path {
    font-size: 11px;
    color: var(--color-text-dim);
    font-family: var(--font-mono);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  /* ─── Doc detail ───────────────────────────────── */

  .doc-detail {
    flex: 1;
    overflow-y: auto;
    padding: 20px 24px;
  }

  .doc-content {
    font-size: 13px;
    line-height: 1.75;
    color: var(--color-text);
    max-width: 640px;
    margin: 0 auto;
  }

  .doc-content :global(h1) {
    font-size: 16px;
    font-weight: 600;
    color: var(--color-text-heading);
    margin: 0 0 16px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--color-border);
    line-height: 1.4;
  }

  .doc-content :global(h2) {
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text);
    margin: 24px 0 8px;
  }

  .doc-content :global(h3) {
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text);
    margin: 16px 0 6px;
  }

  .doc-content :global(img) {
    display: block;
    max-width: 100%;
    max-height: 50vh;
    width: auto;
    height: auto;
    object-fit: contain;
    margin: 12px auto;
    border-radius: 8px;
    border: 1px solid var(--color-border);
  }

  .doc-content :global(p)          { margin: 0 0 12px; }
  .doc-content :global(ul),
  .doc-content :global(ol)         { padding-left: 20px; margin: 0 0 12px; }
  .doc-content :global(li)         { margin: 3px 0; }
  .doc-content :global(hr)         { border: none; border-top: 1px solid var(--color-border); margin: 20px 0; }
  .doc-content :global(blockquote) { border-left: 2px solid var(--color-accent); margin: 0 0 12px; padding: 2px 12px; color: var(--color-text-dim); font-style: italic; }

  .doc-content :global(code) {
    font-family: var(--font-mono);
    font-size: 12px;
    background: var(--color-surface-hover);
    padding: 1px 5px;
    border-radius: 3px;
    color: var(--color-accent);
  }

  .doc-content :global(pre) {
    background: var(--color-surface-hover);
    border: 1px solid var(--color-border);
    border-radius: 6px;
    padding: 12px;
    overflow-x: auto;
    margin: 0 0 12px;
  }

  .doc-content :global(pre code) {
    background: none;
    padding: 0;
    color: var(--color-text);
    font-size: 12px;
  }

  .doc-content :global(a) {
    color: var(--color-accent);
    text-decoration: none;
  }

  .doc-content :global(a:hover) { text-decoration: underline; }

  .doc-content :global(table) {
    width: 100%;
    border-collapse: collapse;
    margin: 0 0 12px;
    font-size: 12px;
  }

  .doc-content :global(th),
  .doc-content :global(td) {
    padding: 6px 10px;
    border: 1px solid var(--color-border);
    text-align: left;
  }

  .doc-content :global(th) {
    background: var(--color-surface-hover);
    color: var(--color-text);
    font-weight: 600;
  }

  .doc-content :global(.doc-error) {
    color: var(--color-error);
    font-family: var(--font-mono);
    font-size: 12px;
  }

  /* ─── Code pane ────────────────────────────────── */

  .code-pane {
    flex: 1;
    display: flex;
    flex-direction: row;
    overflow: hidden;
    min-height: 0;
  }

  .code-sidebar {
    width: 260px;
    flex-shrink: 0;
    overflow-y: auto;
    border-right: 1px solid var(--color-border);
    padding: 6px 0;
    display: flex;
    flex-direction: column;
    align-items: stretch;
  }

  .code-main {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    min-height: 0;
  }

  .tree-entry {
    display: flex;
    align-items: center;
    gap: 7px;
    width: 100%;
    background: none;
    border: none;
    text-align: left;
    font-family: var(--font-mono);
    font-size: 12.5px;
    color: var(--color-text);
    padding: 4px 12px 4px 10px;
    cursor: pointer;
    transition: background var(--transition), color var(--transition);
    border-left: 2px solid transparent;
  }

  .tree-entry:hover {
    background: var(--color-surface-hover);
  }

  .tree-entry.dir {
    color: var(--color-text-heading);
  }

  .tree-entry.file .tree-icon {
    font-size: 10px;
    font-weight: 700;
    width: 14px;
    text-align: center;
    opacity: 0.55;
    color: var(--color-accent);
    flex-shrink: 0;
  }

  .tree-chev {
    font-size: 10px;
    width: 14px;
    text-align: center;
    opacity: 0.5;
    flex-shrink: 0;
  }

  .tree-name {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .tree-entry.file.active {
    background: var(--color-accent-glow);
    border-left-color: var(--color-accent);
    color: var(--color-text-heading);
  }

  .tree-entry.dir.focused {
    background: var(--color-surface-hover);
    border-left-color: var(--color-accent);
  }

  .crumbs {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 4px;
    padding: 10px 16px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
    font-family: var(--font-mono);
    font-size: 12px;
  }

  .doc-crumbs {
    max-width: 640px;
    margin: 0 auto 16px;
    padding: 0 0 10px;
  }

  .crumb {
    background: none;
    border: none;
    padding: 2px 6px;
    border-radius: 4px;
    color: var(--color-text-dim);
    font-family: inherit;
    font-size: inherit;
    cursor: pointer;
    transition: color var(--transition), background var(--transition);
  }

  button.crumb:hover {
    color: var(--color-accent);
    background: var(--color-accent-glow);
  }

  .crumb.current {
    color: var(--color-text-heading);
    cursor: default;
    padding: 2px 6px;
  }

  .crumb-sep {
    color: var(--color-text-dim);
    opacity: 0.5;
    font-size: 11px;
    user-select: none;
  }

  .crumb-meta {
    margin-left: auto;
    font-size: 11px;
    color: var(--color-text-dim);
    opacity: 0.5;
    padding-left: 12px;
  }

  .code-body {
    flex: 1;
    overflow: auto;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  /* ─── Code search ────────────────────────────────── */

  .code-search {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 14px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
    flex-wrap: wrap;
  }

  .code-search .search-input {
    flex: 1;
    min-width: 0;
  }

  .search-meta {
    font-family: var(--font-mono);
    font-size: 11px;
    color: var(--color-text-dim);
    white-space: nowrap;
  }

  .scope-toggle {
    display: flex;
    border: 1px solid var(--color-border);
    border-radius: 6px;
    overflow: hidden;
    flex-shrink: 0;
  }

  .scope-btn {
    background: none;
    border: none;
    padding: 4px 9px;
    font-family: var(--font-mono);
    font-size: 11px;
    color: var(--color-text-dim);
    cursor: pointer;
    transition: color var(--transition), background var(--transition);
  }

  .scope-btn + .scope-btn {
    border-left: 1px solid var(--color-border);
  }

  .scope-btn:hover { color: var(--color-text); }

  .scope-btn.active {
    background: var(--color-accent-glow);
    color: var(--color-accent);
  }

  .code-view :global(mark) {
    background: var(--color-accent-glow);
    color: var(--color-accent);
    border-radius: 2px;
    padding: 0 1px;
    box-shadow: 0 0 0 1px var(--color-accent);
  }

  .code-view :global(mark.active) {
    background: var(--color-accent);
    color: var(--color-bg);
    box-shadow: 0 0 0 2px var(--color-accent);
  }

  .search-input.invalid {
    color: var(--color-error, #e74c3c);
  }

  .search-error {
    color: var(--color-error, #e74c3c);
  }

  .icon-btn {
    background: none;
    border: 1px solid transparent;
    color: var(--color-text-dim);
    cursor: pointer;
    padding: 3px 6px;
    border-radius: 5px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    transition: color var(--transition), background var(--transition), border-color var(--transition);
    font-family: var(--font-mono);
    font-size: 11px;
    line-height: 1;
    flex-shrink: 0;
  }

  .icon-btn:hover:not(:disabled) {
    color: var(--color-text);
    background: var(--color-surface-hover);
  }

  .icon-btn:disabled {
    opacity: 0.35;
    cursor: default;
  }

  .icon-btn.active {
    color: var(--color-accent);
    background: var(--color-accent-glow);
    border-color: var(--color-accent);
  }

  .regex-btn {
    font-weight: 700;
    font-family: var(--font-mono);
    min-width: 24px;
  }

  .nav-arrows {
    display: inline-flex;
    gap: 2px;
    flex-shrink: 0;
  }

  /* ─── Global search results ─────────────────────── */

  .search-results {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 12px 16px 24px;
  }

  .search-file {
    border: 1px solid var(--color-border);
    border-radius: 8px;
    overflow: hidden;
  }

  .search-file-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    background: var(--color-surface-hover);
    border: none;
    padding: 7px 12px;
    cursor: pointer;
    font-family: var(--font-mono);
    font-size: 12px;
    color: var(--color-text-heading);
    text-align: left;
    transition: background var(--transition);
  }

  .search-file-header:hover { background: var(--color-accent-glow); }

  .search-file-path {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .search-file-count {
    font-size: 10px;
    color: var(--color-accent);
    font-weight: 700;
    margin-left: 12px;
    flex-shrink: 0;
  }

  .search-file-hits {
    display: flex;
    flex-direction: column;
  }

  .search-hit {
    display: flex;
    align-items: baseline;
    gap: 10px;
    width: 100%;
    background: none;
    border: none;
    border-top: 1px solid var(--color-border);
    padding: 5px 12px;
    cursor: pointer;
    font-family: var(--font-mono);
    font-size: 11.5px;
    color: var(--color-text);
    text-align: left;
    transition: background var(--transition);
  }

  .search-hit:hover { background: var(--color-surface-hover); }

  .search-hit-line {
    color: var(--color-text-dim);
    opacity: 0.55;
    min-width: 32px;
    text-align: right;
    flex-shrink: 0;
  }

  .search-hit-text {
    white-space: pre;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .search-hit-more {
    padding: 5px 12px;
    border-top: 1px solid var(--color-border);
    font-family: var(--font-mono);
    font-size: 11px;
    color: var(--color-text-dim);
    font-style: italic;
  }

  .code-view {
    flex: 1;
    overflow: auto;
    margin: 0;
    padding: 16px 20px;
    font-family: var(--font-mono);
    font-size: 12.5px;
    line-height: 1.65;
    color: var(--color-text);
    background: none;
    border: none;
    white-space: pre;
    tab-size: 2;
  }

  .code-view :global(code.hljs) {
    background: transparent;
    padding: 0;
    font-size: inherit;
    font-family: inherit;
    color: var(--color-text);
  }

  /* ── Syntax highlighting — тёплая палитра, tokens через CSS-переменные ── */

  .code-view :global(.hljs-comment),
  .code-view :global(.hljs-quote) {
    color: var(--hl-comment);
    font-style: italic;
  }

  .code-view :global(.hljs-keyword),
  .code-view :global(.hljs-selector-tag),
  .code-view :global(.hljs-literal),
  .code-view :global(.hljs-section),
  .code-view :global(.hljs-doctag) {
    color: var(--hl-keyword);
    font-weight: 600;
  }

  .code-view :global(.hljs-string),
  .code-view :global(.hljs-regexp),
  .code-view :global(.hljs-symbol),
  .code-view :global(.hljs-template-tag),
  .code-view :global(.hljs-template-variable) {
    color: var(--hl-string);
  }

  .code-view :global(.hljs-number),
  .code-view :global(.hljs-bullet) {
    color: var(--hl-number);
  }

  .code-view :global(.hljs-title),
  .code-view :global(.hljs-title.class_),
  .code-view :global(.hljs-title.function_),
  .code-view :global(.hljs-name),
  .code-view :global(.hljs-built_in) {
    color: var(--hl-title);
    font-weight: 600;
  }

  .code-view :global(.hljs-type),
  .code-view :global(.hljs-class .hljs-title),
  .code-view :global(.hljs-params) {
    color: var(--hl-type);
  }

  .code-view :global(.hljs-variable),
  .code-view :global(.hljs-attr),
  .code-view :global(.hljs-property),
  .code-view :global(.hljs-attribute) {
    color: var(--hl-attr);
  }

  .code-view :global(.hljs-meta),
  .code-view :global(.hljs-meta .hljs-keyword),
  .code-view :global(.hljs-tag) {
    color: var(--hl-meta);
  }

  .code-view :global(.hljs-operator),
  .code-view :global(.hljs-punctuation) {
    color: var(--color-text-dim);
  }

  .code-view :global(.hljs-emphasis) { font-style: italic; }
  .code-view :global(.hljs-strong)   { font-weight: 700; }
  .code-view :global(.hljs-link)     { color: var(--color-accent); text-decoration: underline; }

  /* ─── Timeline pane ────────────────────────────── */

  .timeline-pane {
    flex: 1;
    overflow-y: auto;
    padding: 20px 28px 32px;
  }

  .timeline-sub {
    font-size: 11px;
    font-family: var(--font-mono);
    color: var(--color-text-dim);
    margin-bottom: 20px;
    letter-spacing: 0.04em;
    text-align: center;
  }

  .timeline {
    display: flex;
    flex-direction: column;
    gap: 24px;
    max-width: 720px;
    margin: 0 auto;
  }

  .day-marker {
    display: flex;
    align-items: baseline;
    gap: 10px;
    margin-bottom: 8px;
  }

  .day-date {
    font-family: var(--font-mono);
    font-size: 12px;
    font-weight: 700;
    color: var(--color-text);
    letter-spacing: 0.04em;
  }

  .day-count {
    font-family: var(--font-mono);
    font-size: 10px;
    color: var(--color-text-dim);
  }

  .day-commits {
    border-left: 1px solid var(--color-border);
    margin-left: 6px;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .commit {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 6px 0 6px 16px;
    position: relative;
  }

  .commit-dot {
    position: absolute;
    left: -4px;
    top: 12px;
    width: 7px;
    height: 7px;
    border-radius: 50%;
    border: 1px solid var(--color-border);
    background: var(--color-bg);
    flex-shrink: 0;
  }

  .commit[data-type="feat"] .commit-dot    { border-color: var(--color-accent); background: var(--color-accent); }
  .commit[data-type="arch"] .commit-dot    { border-color: #9b59b6; background: #9b59b6; }
  .commit[data-type="fix"] .commit-dot     { border-color: #e74c3c; background: #e74c3c; }
  .commit[data-type="design"] .commit-dot  { border-color: #1abc9c; background: #1abc9c; }
  .commit[data-type="docs"] .commit-dot    { border-color: #3498db; background: #3498db; }

  .commit-body-wrap {
    display: flex;
    flex-direction: column;
    gap: 3px;
    min-width: 0;
  }

  .commit-top {
    display: flex;
    align-items: baseline;
    gap: 7px;
    flex-wrap: wrap;
  }

  .commit-scope {
    font-family: var(--font-mono);
    font-size: 10px;
    color: var(--color-accent);
    opacity: 0.8;
    flex-shrink: 0;
  }

  .commit-body {
    font-size: 13px;
    color: var(--color-text);
    line-height: 1.4;
  }

  .commit-hash {
    font-family: var(--font-mono);
    font-size: 10px;
    color: var(--color-text-dim);
    opacity: 0.5;
    flex-shrink: 0;
  }

  .commit-type {
    font-family: var(--font-mono);
    font-size: 10px;
    color: var(--color-text-dim);
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }

  /* ─── Mobile ───────────────────────────────────── */

  @media (max-width: 640px) {
    .overlay { padding: 0; }
    .modal {
      max-width: 100%;
      height: 100vh;
      border-radius: 0;
    }
    .tab { font-size: 12px; padding: 8px 8px 10px; }
    .code-sidebar { width: 180px; }
  }
</style>
