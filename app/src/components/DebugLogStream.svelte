<script lang="ts">
    import { debugLog } from "../lib/debugLog.svelte";

    const VISIBLE = 50;

    const visible = $derived(debugLog.entries.slice(-VISIBLE));
</script>

<div class="log-stream" aria-hidden="true">
    {#each visible as entry, i (entry.id)}
        {@const age = visible.length - 1 - i}
        <div
            class="log-line kind-{entry.kind}"
            style="--age: {age}"
        >
            <span class="marker">
                {#if entry.kind === "req"}→{:else if entry.kind === "res"}←{:else if entry.kind === "err"}✕{:else}·{/if}
            </span>
            <span class="text">{entry.text}</span>
        </div>
    {/each}
</div>

<style>
    .log-stream {
        position: absolute;
        inset: 0;
        pointer-events: none;
        overflow: hidden;
        display: flex;
        flex-direction: column;
        justify-content: flex-start;
        padding: 72px 4vw 2vh;
        font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
        font-size: 12px;
        line-height: 1.55;
        letter-spacing: 0.02em;
        z-index: 2;
    }

    .log-line {
        color: var(--color-accent);
        opacity: calc(max(0, 1 - var(--age) * 0.02) * 0.22);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        animation: slideIn 260ms ease-out;
        will-change: opacity, transform;
    }

    .marker {
        display: inline-block;
        width: 1ch;
        margin-right: 0.75ch;
        opacity: 0.8;
    }

    .kind-err {
        color: var(--color-error);
        opacity: calc(max(0, 1 - var(--age) * 0.02) * 0.35);
    }

    .kind-evt {
        color: var(--color-text-muted);
    }

    @keyframes slideIn {
        from {
            opacity: 0;
            transform: translateY(6px);
        }
    }

    @media (prefers-reduced-motion: reduce) {
        .log-line {
            animation: none;
        }
    }
</style>
