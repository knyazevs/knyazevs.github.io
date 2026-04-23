<script lang="ts">
    import { onMount } from "svelte";
    import { marked, Renderer } from "marked";
    import {
        getDocTree,
        getDocContent,
        type DocSection,
        type DocItem,
    } from "../lib/docs-client.js";

    // Кастомный renderer: mermaid-блоки получают класс .mermaid вместо <code>
    const renderer = new Renderer();
    renderer.code = ({ text, lang }: { text: string; lang?: string }) => {
        if (lang === "mermaid") {
            return `<pre class="mermaid">${text}</pre>`;
        }
        const escaped = text.replace(/&/g, "&amp;").replace(/</g, "&lt;");
        return `<pre><code class="language-${lang ?? ""}">${escaped}</code></pre>`;
    };
    marked.use({ renderer });

    let sections: DocSection[] = $state([]);
    let expandedSections: Set<string> = $state(new Set());
    const readmeCache = new Map<string, string>();
    let readmeHtml: Record<string, string> = $state({});
    let activeItem: DocItem | null = $state(null);

    function toggleSection(label: string) {
        const next = new Set(expandedSections);
        if (next.has(label)) {
            next.delete(label);
        } else {
            next.add(label);
            loadReadme(label);
        }
        expandedSections = next;
    }

    async function loadReadme(label: string) {
        const section = sections.find((s) => s.label === label);
        if (!section?.readmePath) return;
        if (readmeCache.has(section.readmePath)) return;
        try {
            const md = await getDocContent(section.readmePath);
            const html = (await marked(md)) as string;
            readmeCache.set(section.readmePath, html);
            readmeHtml = { ...readmeHtml, [section.readmePath]: html };
        } catch {
            /* молча игнорируем */
        }
    }
    let docHtml: string = $state("");
    let treeLoading = $state(true);
    let contentLoading = $state(false);
    let treeError: string | null = $state(null);
    let articleEl: HTMLElement | undefined = $state();
    let articleFullEl: HTMLElement | undefined = $state();
    let docFullscreen = $state(false);

    function openFullscreen() {
        docFullscreen = true;
    }
    function closeFullscreen() {
        docFullscreen = false;
    }

    function onFullscreenKey(e: KeyboardEvent) {
        if (e.key === "Escape") closeFullscreen();
    }

    // ─── Fullscreen modal ──────────────────────────
    let modalOpen = $state(false);
    let modalSvg = $state("");
    let scale = $state(1);
    let tx = $state(0);
    let ty = $state(0);
    let dragging = false;
    let dragStart = { x: 0, y: 0, tx: 0, ty: 0 };

    function openDiagram(svgEl: SVGSVGElement) {
        // Вычисляем натуральные размеры из viewBox
        const viewBox = svgEl.getAttribute("viewBox");
        let natW = parseFloat(svgEl.getAttribute("width") ?? "800");
        let natH = parseFloat(svgEl.getAttribute("height") ?? "600");
        if (viewBox) {
            const parts = viewBox.trim().split(/[\s,]+/);
            if (parts.length === 4) {
                natW = parseFloat(parts[2]);
                natH = parseFloat(parts[3]);
            }
        }

        // Вписываем в 90% viewport с сохранением пропорций
        const maxW = window.innerWidth * 0.9;
        const maxH = window.innerHeight * 0.85;
        const ratio = natW / natH;
        let fitW = maxW;
        let fitH = fitW / ratio;
        if (fitH > maxH) {
            fitH = maxH;
            fitW = fitH * ratio;
        }

        // Клонируем SVG и ставим вычисленные размеры
        const clone = svgEl.cloneNode(true) as SVGSVGElement;
        clone.setAttribute("width", String(Math.round(fitW)));
        clone.setAttribute("height", String(Math.round(fitH)));
        clone.style.display = "block";

        modalSvg = clone.outerHTML;
        scale = 1;
        tx = 0;
        ty = 0;
        modalOpen = true;
    }

    function closeModal() {
        modalOpen = false;
    }

    function onOverlayKey(e: KeyboardEvent) {
        if (e.key === "Escape") closeModal();
    }

    function onWheel(e: WheelEvent) {
        e.preventDefault();
        const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
        const cx = e.clientX - rect.left;
        const cy = e.clientY - rect.top;
        const delta = e.deltaY < 0 ? 1.12 : 1 / 1.12;
        const newScale = Math.max(0.2, Math.min(12, scale * delta));
        tx = cx - (cx - tx) * (newScale / scale);
        ty = cy - (cy - ty) * (newScale / scale);
        scale = newScale;
    }

    function onDragStart(e: MouseEvent) {
        if (e.button !== 0) return;
        dragging = true;
        dragStart = { x: e.clientX, y: e.clientY, tx, ty };
        (e.currentTarget as HTMLElement).setPointerCapture?.(
            (e as PointerEvent).pointerId ?? 0,
        );
    }

    function onDragMove(e: MouseEvent) {
        if (!dragging) return;
        tx = dragStart.tx + (e.clientX - dragStart.x);
        ty = dragStart.ty + (e.clientY - dragStart.y);
    }

    function onDragEnd() {
        dragging = false;
    }

    function zoomBy(factor: number) {
        scale = Math.max(0.2, Math.min(12, scale * factor));
    }

    // ─── Theme toggle ─────────────────────────────────
    type ThemeMode = "auto" | "light" | "dark";
    let themeMode: ThemeMode = $state("auto");

    function initTheme() {
        themeMode = (localStorage.getItem("theme") as ThemeMode) || "auto";
    }

    function toggleTheme() {
        const cycle: ThemeMode[] = ["auto", "light", "dark"];
        themeMode = cycle[(cycle.indexOf(themeMode) + 1) % 3];
        localStorage.setItem("theme", themeMode);
        const sys = window.matchMedia("(prefers-color-scheme: light)").matches
            ? "light"
            : "dark";
        document.documentElement.setAttribute(
            "data-theme",
            themeMode === "auto" ? sys : themeMode,
        );
    }

    onMount(async () => {
        initTheme();
        try {
            const tree = await getDocTree();
            // Профиль первым, остальные по алфавиту
            sections = tree.sort((a, b) => {
                if (a.id === "profile") return -1;
                if (b.id === "profile") return 1;
                return a.id.localeCompare(b.id);
            });
            // Профиль открыт по умолчанию, сразу загружаем его README
            const profile = sections.find((s) => s.id === "profile");
            if (profile) {
                expandedSections = new Set([profile.label]);
                loadReadme(profile.label);
            }
        } catch {
            treeError = "Не удалось загрузить документацию";
        } finally {
            treeLoading = false;
        }
    });

    async function openDoc(item: DocItem) {
        activeItem = item;
        docHtml = "";
        contentLoading = true;
        try {
            const md = await getDocContent(item.path);
            docHtml = await marked(md);
        } catch {
            docHtml = '<p class="doc-error">Не удалось загрузить документ.</p>';
        } finally {
            contentLoading = false;
        }
    }

    function backToNav() {
        activeItem = null;
        docHtml = "";
    }

    function attachMermaid(container: HTMLElement) {
        const nodes = Array.from(
            container.querySelectorAll<HTMLElement>("pre.mermaid"),
        );
        if (nodes.length === 0) return;
        import("mermaid").then(async ({ default: mermaid }) => {
            mermaid.initialize({
                startOnLoad: false,
                ...(window.matchMedia("(prefers-color-scheme: light)").matches
                    ? {
                          theme: "default",
                          themeVariables: {
                              background: "#f4f4fb",
                              primaryColor: "#e0e0f4",
                              primaryTextColor: "#1c1c30",
                              lineColor: "#9090c0",
                              edgeLabelBackground: "#ededf7",
                          },
                      }
                    : {
                          theme: "dark",
                          themeVariables: {
                              background: "#0a0a0f",
                              primaryColor: "#1a1a30",
                              primaryTextColor: "#c8c8d8",
                              lineColor: "#555570",
                              edgeLabelBackground: "#111118",
                          },
                      }),
            });
            await mermaid.run({ nodes });
            nodes.forEach((node) => {
                if (node.querySelector(".diagram-expand")) return;
                const svg = node.querySelector("svg");
                if (!svg) return;
                node.style.position = "relative";
                const btn = document.createElement("button");
                btn.className = "diagram-expand";
                btn.setAttribute("aria-label", "Развернуть диаграмму");
                btn.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/>
          <line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/>
        </svg>`;
                btn.onclick = () => openDiagram(svg);
                node.appendChild(btn);
            });
        });
    }

    $effect(() => {
        if (docHtml && articleEl) attachMermaid(articleEl);
    });
    $effect(() => {
        if (docHtml && articleFullEl && docFullscreen)
            attachMermaid(articleFullEl);
    });
</script>

<div class="panel-inner">
    <!-- Шапка всегда видна -->
    <header class="panel-header">
        <div class="author-name">Sergey Knyazev</div>
        <div class="author-role">
            <span class="role-prompt">›</span> Technical Lead · Architect<span
                class="role-cursor">_</span
            >
        </div>
    </header>

    <!-- Навигация -->
    {#if !activeItem}
        <nav class="doc-nav">
            {#if treeLoading}
                <div class="status-msg">Загрузка…</div>
            {:else if treeError}
                <div class="status-msg error">{treeError}</div>
            {:else}
                {#each sections as section}
                    {@const open = expandedSections.has(section.label)}
                    <div class="nav-section">
                        <button
                            class="nav-section-title"
                            onclick={() => toggleSection(section.label)}
                            aria-expanded={open}
                        >
                            <span>{section.label}</span>
                            <svg
                                class="chevron"
                                class:open
                                width="10"
                                height="10"
                                viewBox="0 0 10 10"
                                fill="none"
                                stroke="currentColor"
                                stroke-width="1.8"
                            >
                                <polyline points="2,3 5,7 8,3" />
                            </svg>
                        </button>
                        {#if open}
                            {#if section.readmePath && readmeHtml[section.readmePath]}
                                <div class="section-readme">
                                    {@html readmeHtml[section.readmePath]}
                                </div>
                            {/if}
                            {#if section.items.length > 0}
                                <ul>
                                    {#each section.items as item}
                                        <li>
                                            <button
                                                class="nav-link"
                                                onclick={() => openDoc(item)}
                                            >
                                                {item.label}
                                            </button>
                                        </li>
                                    {/each}
                                </ul>
                            {/if}
                        {/if}
                    </div>
                {/each}
            {/if}
        </nav>

        <!-- Контент документа -->
    {:else}
        <div class="doc-view">
            <div class="doc-toolbar">
                <button class="back-btn" onclick={backToNav}>← Назад</button>
                {#if !contentLoading}
                    <button
                        class="expand-btn"
                        onclick={openFullscreen}
                        aria-label="Открыть на весь экран"
                    >
                        <svg
                            width="13"
                            height="13"
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            stroke-width="2.5"
                        >
                            <polyline points="15 3 21 3 21 9" /><polyline
                                points="9 21 3 21 3 15"
                            />
                            <line x1="21" y1="3" x2="14" y2="10" /><line
                                x1="3"
                                y1="21"
                                x2="10"
                                y2="14"
                            />
                        </svg>
                    </button>
                {/if}
            </div>
            {#if contentLoading}
                <div class="status-msg">Загрузка…</div>
            {:else}
                <article class="doc-content" bind:this={articleEl}>
                    {@html docHtml}
                </article>
            {/if}
        </div>
    {/if}

    <!-- Контакты всегда внизу -->
    <footer class="panel-footer">
        <a href="mailto:s_knyazev@vk.com" class="contact-link">Email</a>
        <a
            href="https://linkedin.com"
            class="contact-link"
            target="_blank"
            rel="noopener">LinkedIn</a
        >
        <a
            href="https://github.com/knyazevs"
            class="contact-link"
            target="_blank"
            rel="noopener">GitHub</a
        >
        <button
            class="theme-btn"
            onclick={toggleTheme}
            aria-label="Переключить тему: {themeMode}"
        >
            {#if themeMode === "light"}
                <!-- Sun -->
                <svg
                    width="14"
                    height="14"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                >
                    <circle cx="12" cy="12" r="5" />
                    <line x1="12" y1="1" x2="12" y2="3" /><line
                        x1="12"
                        y1="21"
                        x2="12"
                        y2="23"
                    />
                    <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" /><line
                        x1="18.36"
                        y1="18.36"
                        x2="19.78"
                        y2="19.78"
                    />
                    <line x1="1" y1="12" x2="3" y2="12" /><line
                        x1="21"
                        y1="12"
                        x2="23"
                        y2="12"
                    />
                    <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" /><line
                        x1="18.36"
                        y1="5.64"
                        x2="19.78"
                        y2="4.22"
                    />
                </svg>
            {:else if themeMode === "dark"}
                <!-- Moon -->
                <svg
                    width="14"
                    height="14"
                    viewBox="0 0 24 24"
                    fill="currentColor"
                >
                    <path
                        d="M21 12.79A9 9 0 1 1 11.21 3a7 7 0 0 0 9.79 9.79z"
                    />
                </svg>
            {:else}
                <!-- Auto: half-sun/half-moon -->
                <svg
                    width="14"
                    height="14"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                >
                    <circle cx="12" cy="12" r="5" />
                    <line x1="12" y1="1" x2="12" y2="3" />
                    <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
                    <line x1="1" y1="12" x2="3" y2="12" />
                    <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
                    <line x1="12" y1="21" x2="12" y2="23" />
                    <path d="M17 12a5 5 0 0 1-5 5" stroke-dasharray="2 2" />
                </svg>
            {/if}
        </button>
    </footer>
</div>

<!-- Fullscreen diagram modal -->
{#if modalOpen}
    <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
    <div
        class="diagram-modal"
        role="dialog"
        aria-modal="true"
        aria-label="Диаграмма"
        onkeydown={onOverlayKey}
        onwheel={onWheel}
        tabindex="-1"
    >
        <!-- svelte-ignore a11y_no_static_element_interactions -->
        <div
            class="diagram-canvas"
            style="transform: translate({tx}px, {ty}px) scale({scale}); cursor: {dragging
                ? 'grabbing'
                : 'grab'}"
            onmousedown={onDragStart}
            onmousemove={onDragMove}
            onmouseup={onDragEnd}
            onmouseleave={onDragEnd}
        >
            {@html modalSvg}
        </div>

        <div class="modal-controls">
            <button
                class="modal-btn"
                onclick={() => zoomBy(1.25)}
                aria-label="Увеличить">+</button
            >
            <button
                class="modal-btn"
                onclick={() => zoomBy(0.8)}
                aria-label="Уменьшить">−</button
            >
            <button
                class="modal-btn"
                onclick={() => {
                    scale = 1;
                    tx = 0;
                    ty = 0;
                }}
                aria-label="Сбросить">⊙</button
            >
        </div>

        <button class="modal-close" onclick={closeModal} aria-label="Закрыть">
            <svg
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2.5"
            >
                <line x1="18" y1="6" x2="6" y2="18" /><line
                    x1="6"
                    y1="6"
                    x2="18"
                    y2="18"
                />
            </svg>
        </button>
    </div>
{/if}

<!-- Fullscreen document modal -->
{#if docFullscreen}
    <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
    <div
        class="doc-fullscreen"
        role="dialog"
        aria-modal="true"
        aria-label={activeItem?.label}
        onkeydown={onFullscreenKey}
        tabindex="-1"
    >
        <div class="doc-fullscreen-inner">
            <article
                class="doc-content doc-fullscreen-content"
                bind:this={articleFullEl}
            >
                {@html docHtml}
            </article>
        </div>
        <button
            class="modal-close"
            onclick={closeFullscreen}
            aria-label="Закрыть"
        >
            <svg
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2.5"
            >
                <line x1="18" y1="6" x2="6" y2="18" /><line
                    x1="6"
                    y1="6"
                    x2="18"
                    y2="18"
                />
            </svg>
        </button>
    </div>
{/if}

<style>
    .panel-inner {
        display: flex;
        flex-direction: column;
        min-height: 100vh;
        padding: 24px 0;
    }

    /* ─── Header ─────────────────────────────────── */

    .panel-header {
        padding: 0 24px 24px;
        margin-bottom: 20px;
        flex-shrink: 0;
    }

    .author-name {
        font-size: 26px;
        font-weight: 700;
        color: var(--color-text-heading);
        letter-spacing: -0.02em;
        line-height: 1.15;
        margin-bottom: 8px;
        text-shadow:
            0 0 40px var(--color-accent-glow),
            0 0 80px var(--color-accent-glow);
    }

    .author-role {
        font-size: 11px;
        color: var(--color-accent);
        font-family: var(--font-mono);
        letter-spacing: 0.06em;
        opacity: 0.75;
        display: flex;
        align-items: center;
        gap: 5px;
    }

    .role-prompt {
        color: var(--color-text-dim);
        font-size: 13px;
    }

    .role-cursor {
        animation: cursorBlink 1.2s step-end infinite;
        color: var(--color-accent);
    }

    @keyframes cursorBlink {
        0%,
        100% {
            opacity: 1;
        }
        50% {
            opacity: 0;
        }
    }

    /* ─── Nav ────────────────────────────────────── */

    .doc-nav {
        flex: 1;
        overflow-y: auto;
        overflow-x: hidden;
        scrollbar-gutter: stable;
        padding: 0 24px;
    }

    .nav-section {
        margin-bottom: 24px;
    }

    .nav-section-title {
        display: flex;
        align-items: center;
        justify-content: space-between;
        width: 100%;
        font-size: 10px;
        font-weight: 700;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        color: var(--color-text-dim);
        margin-bottom: 4px;
        font-family: var(--font-mono);
        background: none;
        border: none;
        padding: 4px 0;
        cursor: pointer;
        transition: color var(--transition);
    }

    .nav-section-title:hover {
        color: var(--color-text);
    }

    .chevron {
        opacity: 0.5;
        transition:
            transform 0.2s ease,
            opacity var(--transition);
        flex-shrink: 0;
    }

    .chevron.open {
        transform: rotate(180deg);
        opacity: 0.8;
    }

    .nav-section-title:hover .chevron {
        opacity: 1;
    }

    ul {
        list-style: none;
    }

    .section-readme {
        font-size: 11px;
        line-height: 1.65;
        color: var(--color-text-muted);
        padding: 6px 0 8px;
        border-bottom: 1px solid var(--color-border);
        margin-bottom: 4px;
    }

    .section-readme :global(p) {
        margin: 0 0 4px;
    }
    .section-readme :global(p:last-child) {
        margin-bottom: 0;
    }
    .section-readme :global(strong) {
        color: var(--color-text);
        font-weight: 600;
    }
    .section-readme :global(a) {
        color: var(--color-accent);
        text-decoration: none;
    }
    .section-readme :global(ul),
    .section-readme :global(ol) {
        padding-left: 14px;
        margin: 2px 0;
    }
    .section-readme :global(li) {
        margin: 1px 0;
    }

    .nav-link {
        display: block;
        width: 100%;
        text-align: left;
        padding: 5px 8px;
        margin: 1px -8px;
        border-radius: 4px;
        border: none;
        background: none;
        color: var(--color-text);
        font-size: 13px;
        font-family: var(--font-sans);
        cursor: pointer;
        transition:
            background var(--transition),
            color var(--transition);
    }

    .nav-link:hover {
        background: var(--color-surface-hover);
        color: var(--color-text-heading);
        box-shadow: inset 2px 0 0 var(--color-accent);
    }

    /* ─── Doc view ───────────────────────────────── */

    .doc-view {
        flex: 1;
        overflow-y: auto;
        display: flex;
        flex-direction: column;
        min-height: 0;
    }

    .doc-toolbar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 0 24px;
        margin-bottom: 16px;
    }

    .back-btn {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        padding: 4px 0;
        background: none;
        border: none;
        color: var(--color-text-dim);
        font-size: 12px;
        font-family: var(--font-mono);
        cursor: pointer;
        transition: color var(--transition);
    }

    .back-btn:hover {
        color: var(--color-accent);
    }

    .expand-btn {
        width: 26px;
        height: 26px;
        display: flex;
        align-items: center;
        justify-content: center;
        border: 1px solid var(--color-border);
        border-radius: 5px;
        background: none;
        color: var(--color-text-dim);
        cursor: pointer;
        transition:
            color var(--transition),
            border-color var(--transition);
    }

    .expand-btn:hover {
        color: var(--color-accent);
        border-color: var(--color-accent);
    }

    /* ─── Fullscreen doc modal ───────────────────────── */

    .doc-fullscreen {
        position: fixed;
        inset: 0;
        z-index: 900;
        background: var(--color-bg);
        display: flex;
        flex-direction: column;
        outline: none;
    }

    .doc-fullscreen-inner {
        flex: 1;
        overflow-y: auto;
        padding: 48px max(48px, 10vw);
        max-width: 860px;
        width: 100%;
        margin: 0 auto;
        box-sizing: border-box;
    }

    .doc-fullscreen-content {
        padding: 0;
        font-size: 15px;
        line-height: 1.8;
    }

    .doc-content {
        flex: 1;
        padding: 0 24px;
        overflow-y: auto;
        font-size: 13px;
        line-height: 1.75;
        color: var(--color-text);
    }

    /* Markdown styles */
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
        letter-spacing: 0.02em;
    }

    .doc-content :global(h3) {
        font-size: 13px;
        font-weight: 600;
        color: var(--color-text);
        margin: 16px 0 6px;
    }

    .doc-content :global(p) {
        margin: 0 0 12px;
    }

    .doc-content :global(ul),
    .doc-content :global(ol) {
        padding-left: 20px;
        margin: 0 0 12px;
    }

    .doc-content :global(li) {
        margin: 3px 0;
    }

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

    .doc-content :global(blockquote) {
        border-left: 2px solid var(--color-accent);
        margin: 0 0 12px;
        padding: 2px 12px;
        color: var(--color-text-dim);
        font-style: italic;
    }

    .doc-content :global(hr) {
        border: none;
        border-top: 1px solid var(--color-border);
        margin: 20px 0;
    }

    .doc-content :global(a) {
        color: var(--color-accent);
        text-decoration: none;
    }

    .doc-content :global(a:hover) {
        text-decoration: underline;
    }

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

    /* ─── Status ─────────────────────────────────── */

    .status-msg {
        padding: 0 24px;
        font-size: 12px;
        color: var(--color-text-dim);
        font-family: var(--font-mono);
    }

    .status-msg.error {
        color: var(--color-error);
    }

    .doc-content :global(.doc-error) {
        color: var(--color-error);
        font-family: var(--font-mono);
        font-size: 12px;
    }

    /* ─── Expand button on diagrams ──────────────── */

    .doc-content :global(.diagram-expand) {
        position: absolute;
        top: 8px;
        right: 8px;
        width: 28px;
        height: 28px;
        border-radius: 6px;
        border: 1px solid var(--color-border);
        background: var(--color-surface);
        color: var(--color-text-dim);
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        opacity: 0;
        transition:
            opacity var(--transition),
            color var(--transition);
    }

    .doc-content :global(pre.mermaid:hover .diagram-expand),
    .doc-content :global(.diagram-expand:focus) {
        opacity: 1;
    }

    .doc-content :global(.diagram-expand:hover) {
        color: var(--color-accent);
        border-color: var(--color-accent);
    }

    /* ─── Fullscreen modal ───────────────────────── */

    .diagram-modal {
        position: fixed;
        inset: 0;
        z-index: 1000;
        background: var(--color-overlay-bg);
        display: flex;
        align-items: center;
        justify-content: center;
        outline: none;
    }

    .diagram-canvas {
        user-select: none;
        transform-origin: center center;
    }

    .diagram-canvas :global(svg) {
        max-width: 90vw;
        max-height: 85vh;
        display: block;
    }

    .modal-controls {
        position: fixed;
        bottom: 24px;
        left: 50%;
        transform: translateX(-50%);
        display: flex;
        gap: 8px;
        background: var(--color-surface);
        border: 1px solid var(--color-border);
        border-radius: 10px;
        padding: 6px 8px;
    }

    .modal-btn {
        width: 32px;
        height: 32px;
        border-radius: 6px;
        border: none;
        background: none;
        color: var(--color-text);
        font-size: 18px;
        line-height: 1;
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        transition:
            background var(--transition),
            color var(--transition);
    }

    .modal-btn:hover {
        background: var(--color-surface-hover);
        color: var(--color-accent);
    }

    .modal-close {
        position: fixed;
        top: 20px;
        right: 24px;
        width: 36px;
        height: 36px;
        border-radius: 8px;
        border: 1px solid var(--color-border);
        background: var(--color-surface);
        color: var(--color-text-dim);
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        transition:
            color var(--transition),
            border-color var(--transition);
    }

    .modal-close:hover {
        color: var(--color-text-heading);
        border-color: var(--color-text-dim);
    }

    /* ─── Footer ─────────────────────────────────── */

    .panel-footer {
        padding: 16px 24px 0;
        border-top: 1px solid var(--color-border);
        margin-top: 16px;
        display: flex;
        gap: 16px;
        flex-shrink: 0;
    }

    .contact-link {
        font-size: 12px;
        color: var(--color-text-dim);
        text-decoration: none;
        font-family: var(--font-mono);
        transition: color var(--transition);
    }

    .contact-link:hover {
        color: var(--color-accent);
    }

    .theme-btn {
        margin-left: auto;
        width: 28px;
        height: 28px;
        border-radius: 6px;
        border: 1px solid var(--color-border);
        background: none;
        color: var(--color-text-dim);
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        transition:
            color var(--transition),
            border-color var(--transition),
            background var(--transition);
        flex-shrink: 0;
    }

    .theme-btn:hover {
        color: var(--color-accent);
        border-color: var(--color-accent);
        background: var(--color-accent-glow);
    }
</style>
