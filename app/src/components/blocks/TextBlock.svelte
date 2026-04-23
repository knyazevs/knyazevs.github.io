<script lang="ts">
    import { marked } from "marked";

    interface Props {
        id: string;
        content: string;
        sources?: string[];
        streaming?: boolean;
    }

    let { id, content, sources = [], streaming = false }: Props = $props();

    const DETAIL_MARKER = "---DETAIL---";

    // ADR-024: санитайзер — визуальные элементы идут отдельными блоками, не в тексте.
    function sanitizeMarkdown(text: string): string {
        return text
            .split("\n")
            .filter((line) => !/^\s*!\[[^\]]*\]\([^)\s]+\)\s*$/.test(line))
            .join("\n")
            .replace(
                /(?<!!)\[([^\]]+)\]\(([^)\s]+)\)/g,
                (match, title, url) => (url.startsWith("#") ? match : title),
            );
    }

    function renderMd(text: string): string {
        let html = marked.parse(sanitizeMarkdown(text), { async: false }) as string;
        if (sources.length) {
            html = html.replace(/\[(\d+)\]/g, (match, num) => {
                const idx = parseInt(num, 10) - 1;
                if (idx < 0 || idx >= sources.length) return match;
                return `<button class="cite-badge" data-source-idx="${idx}">${num}</button>`;
            });
        }
        return html;
    }

    const parts = $derived.by(() => {
        const idx = content.indexOf(DETAIL_MARKER);
        if (idx < 0) return { summary: content, detail: "" };
        return {
            summary: content.slice(0, idx).trim(),
            detail: content.slice(idx + DETAIL_MARKER.length).trim(),
        };
    });

    let detailOpen = $state(false);
</script>

<div class="text-block md" data-block-id={id} class:streaming>
    {#if streaming && !content}
        <span class="state-hint">Думаю…</span>
    {:else}
        {@html renderMd(parts.summary)}{#if streaming && !parts.detail}<span class="stream-cursor">▊</span>{/if}
        {#if !streaming && parts.detail}
            {#if detailOpen}
                <div class="detail">{@html renderMd(parts.detail)}</div>
                <button class="detail-toggle" onclick={() => (detailOpen = false)}>
                    Свернуть
                </button>
            {:else}
                <button class="detail-toggle" onclick={() => (detailOpen = true)}>
                    Подробнее
                </button>
            {/if}
        {/if}
    {/if}
</div>

<style>
    .text-block {
        font-size: 14px;
        line-height: 1.6;
        color: var(--color-text);
    }
    .text-block :global(p) {
        margin: 0 0 10px;
    }
    .text-block :global(p:last-child) {
        margin-bottom: 0;
    }
    .text-block :global(ul),
    .text-block :global(ol) {
        margin: 6px 0 10px 20px;
        padding: 0;
    }
    .text-block :global(li) {
        margin: 3px 0;
    }
    .text-block :global(strong) {
        color: var(--color-text-heading, #fff);
        font-weight: 600;
    }
    .text-block :global(h1),
    .text-block :global(h2),
    .text-block :global(h3) {
        font-size: 14px;
        font-weight: 600;
        color: var(--color-text-heading, #fff);
        margin: 12px 0 6px;
    }
    .text-block :global(code) {
        padding: 1px 5px;
        border-radius: 4px;
        background: rgba(255, 255, 255, 0.06);
        font-family: var(--font-mono);
        font-size: 12px;
    }
    .text-block :global(pre) {
        padding: 10px 12px;
        border-radius: 8px;
        background: rgba(0, 0, 0, 0.4);
        overflow-x: auto;
        margin: 8px 0;
    }
    .text-block :global(pre code) {
        padding: 0;
        background: transparent;
    }
    .text-block :global(blockquote) {
        border-left: 2px solid var(--color-border);
        padding-left: 10px;
        margin: 8px 0;
        color: rgba(255, 255, 255, 0.7);
    }
    .text-block :global(.cite-badge) {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 18px;
        height: 18px;
        padding: 0 5px;
        margin: 0 2px;
        border-radius: 9px;
        border: 1px solid var(--color-border);
        background: var(--color-surface);
        color: var(--color-text);
        font-family: var(--font-mono);
        font-size: 10px;
        cursor: pointer;
        vertical-align: baseline;
    }
    .text-block :global(.cite-badge:hover) {
        color: var(--color-accent);
        border-color: var(--color-accent);
    }
    .detail {
        margin-top: 8px;
    }
    .detail-toggle {
        display: inline-block;
        margin-top: 6px;
        padding: 3px 10px;
        border-radius: 6px;
        border: 1px solid var(--color-border);
        background: transparent;
        color: var(--color-text);
        font-family: var(--font-mono);
        font-size: 11px;
        cursor: pointer;
        transition: color 0.15s, border-color 0.15s;
    }
    .detail-toggle:hover {
        color: var(--color-accent);
        border-color: var(--color-accent);
    }
    .stream-cursor {
        display: inline-block;
        margin-left: 2px;
        opacity: 0.7;
        animation: blink 1s steps(2, start) infinite;
    }
    .state-hint {
        color: rgba(255, 255, 255, 0.5);
        font-style: italic;
    }
    @keyframes blink {
        to {
            visibility: hidden;
        }
    }
</style>
