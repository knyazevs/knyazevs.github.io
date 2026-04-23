<script lang="ts">
    import { openImagePreview } from "../../lib/imagePreview.svelte";

    interface Props {
        url: string;
        caption?: string | null;
        alt?: string | null;
        link?: string | null;
    }

    let { url, caption = null, alt = null, link = null }: Props = $props();

    function expand() {
        openImagePreview({ url, caption, alt });
    }
</script>

<figure class="image-block">
    {#if link}
        <a href={link} target="_blank" rel="noopener noreferrer">
            <img src={url} alt={alt ?? caption ?? ""} loading="lazy" />
        </a>
    {:else}
        <button type="button" class="zoom-btn" onclick={expand} aria-label="Открыть на весь экран">
            <img src={url} alt={alt ?? caption ?? ""} loading="lazy" />
        </button>
    {/if}
    {#if caption}
        <figcaption>{caption}</figcaption>
    {/if}
</figure>

<style>
    .image-block {
        margin: 0 auto;
        align-self: center;
        border-radius: 10px;
        overflow: hidden;
        background: rgba(255, 255, 255, 0.03);
        border: 1px solid rgba(255, 255, 255, 0.06);
        max-width: 320px;
    }
    .image-block img {
        display: block;
        max-width: 100%;
        max-height: 40vh;
        width: auto;
        height: auto;
        object-fit: contain;
        margin: 0 auto;
    }
    figcaption {
        padding: 6px 10px;
        font-size: 12px;
        color: rgba(255, 255, 255, 0.6);
        line-height: 1.4;
    }
    a {
        display: block;
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
</style>
