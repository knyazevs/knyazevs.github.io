<script lang="ts">
    import { marked } from "marked";
    import { openImagePreview } from "../../lib/imagePreview.svelte";

    interface ImageRef {
        url: string;
        alt?: string | null;
        caption?: string | null;
    }

    interface Props {
        text: string;
        image: ImageRef;
        imagePosition?: "left" | "right";
    }

    let { text, image, imagePosition = "left" }: Props = $props();
    const textHtml = $derived(marked.parse(text, { async: false }) as string);
</script>

<div class="twi-block" class:reverse={imagePosition === "right"}>
    <figure class="twi-image">
        <button
            type="button"
            class="zoom-btn"
            onclick={() => openImagePreview({ url: image.url, caption: image.caption, alt: image.alt })}
            aria-label="Открыть на весь экран"
        >
            <img src={image.url} alt={image.alt ?? image.caption ?? ""} loading="lazy" />
        </button>
        {#if image.caption}
            <figcaption>{image.caption}</figcaption>
        {/if}
    </figure>
    <div class="twi-text md">{@html textHtml}</div>
</div>

<style>
    .twi-block {
        display: grid;
        grid-template-columns: minmax(110px, 140px) 1fr;
        gap: 14px;
        align-items: start;
        padding: 12px;
        border-radius: 10px;
        background: rgba(255, 255, 255, 0.03);
        border: 1px solid rgba(255, 255, 255, 0.06);
    }
    .twi-block.reverse {
        grid-template-columns: 1fr minmax(110px, 140px);
    }
    .twi-block.reverse .twi-image {
        order: 2;
    }
    .twi-image {
        margin: 0;
        border-radius: 8px;
        overflow: hidden;
    }
    .twi-image img {
        display: block;
        width: 100%;
        height: auto;
        max-height: 180px;
        object-fit: cover;
    }
    .zoom-btn {
        display: block;
        width: 100%;
        padding: 0;
        margin: 0;
        border: none;
        background: none;
        cursor: zoom-in;
    }
    figcaption {
        padding-top: 4px;
        font-size: 11px;
        color: rgba(255, 255, 255, 0.55);
        line-height: 1.35;
    }
    .twi-text {
        font-size: 13px;
        line-height: 1.55;
        color: var(--color-text);
    }
    .twi-text :global(p) { margin: 0 0 8px; }
    .twi-text :global(p:last-child) { margin-bottom: 0; }
    .twi-text :global(strong) { color: var(--color-text-heading, #fff); font-weight: 600; }
    .twi-text :global(ul), .twi-text :global(ol) {
        margin: 4px 0 6px 18px;
        padding: 0;
    }
    .twi-text :global(li) { margin: 2px 0; }

    @media (max-width: 520px) {
        .twi-block,
        .twi-block.reverse {
            grid-template-columns: 1fr;
        }
        .twi-block.reverse .twi-image {
            order: 0;
        }
        .twi-image img {
            max-height: 220px;
        }
    }
</style>
