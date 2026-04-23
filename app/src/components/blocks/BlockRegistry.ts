import type { Component } from "svelte";
import ImageBlock from "./ImageBlock.svelte";
import ImageGalleryBlock from "./ImageGalleryBlock.svelte";
import LinkListBlock from "./LinkListBlock.svelte";
import TextWithImageBlock from "./TextWithImageBlock.svelte";
import TextBlock from "./TextBlock.svelte";

// ADR-025: text — такой же блок, как остальные, просто стримится по токенам.
// Все блоки имеют обязательный id (используется для связывания token-дельт с блоком).
export type UiBlock =
    | {
          id: string;
          type: "text";
          content: string;
          streaming?: boolean;
      }
    | {
          id: string;
          type: "image";
          url: string;
          caption?: string | null;
          alt?: string | null;
          link?: string | null;
      }
    | {
          id: string;
          type: "image_gallery";
          images: Array<{ url: string; caption?: string | null }>;
      }
    | {
          id: string;
          type: "link_list";
          items: Array<{
              url: string;
              title: string;
              description?: string | null;
          }>;
      }
    | {
          id: string;
          type: "text_with_image";
          text: string;
          image: { url: string; alt?: string | null; caption?: string | null };
          imagePosition?: "left" | "right";
      };

export const BLOCK_REGISTRY: Record<UiBlock["type"], Component<any>> = {
    text: TextBlock,
    image: ImageBlock,
    image_gallery: ImageGalleryBlock,
    link_list: LinkListBlock,
    text_with_image: TextWithImageBlock,
};
