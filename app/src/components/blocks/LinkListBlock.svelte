<script lang="ts">
    interface LinkItem {
        url: string;
        title: string;
        description?: string | null;
    }

    interface Props {
        items: LinkItem[];
    }

    let { items }: Props = $props();

    function hostOf(url: string): string {
        try {
            return new URL(url).hostname.replace(/^www\./, "");
        } catch {
            return url;
        }
    }
</script>

<ul class="link-list-block">
    {#each items as item}
        <li>
            <a href={item.url} target="_blank" rel="noopener noreferrer">
                <div class="link-title">{item.title}</div>
                {#if item.description}
                    <div class="link-desc">{item.description}</div>
                {/if}
                <div class="link-host">{hostOf(item.url)}</div>
            </a>
        </li>
    {/each}
</ul>

<style>
    .link-list-block {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 6px;
    }
    li {
        margin: 0;
    }
    a {
        display: block;
        padding: 10px 12px;
        border-radius: 8px;
        background: rgba(255, 255, 255, 0.03);
        border: 1px solid rgba(255, 255, 255, 0.06);
        color: inherit;
        text-decoration: none;
        transition: background 0.15s ease, border-color 0.15s ease;
    }
    a:hover {
        background: rgba(255, 255, 255, 0.06);
        border-color: rgba(255, 255, 255, 0.12);
    }
    .link-title {
        font-weight: 500;
        line-height: 1.3;
    }
    .link-desc {
        margin-top: 2px;
        font-size: 12px;
        color: rgba(255, 255, 255, 0.6);
        line-height: 1.4;
    }
    .link-host {
        margin-top: 4px;
        font-size: 11px;
        color: rgba(255, 255, 255, 0.45);
    }
</style>
