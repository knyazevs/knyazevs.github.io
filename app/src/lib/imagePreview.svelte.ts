export interface ImagePreview {
    url: string;
    caption?: string | null;
    alt?: string | null;
}

export const imagePreviewState = $state<{ value: ImagePreview | null }>({ value: null });

export function openImagePreview(preview: ImagePreview): void {
    imagePreviewState.value = preview;
}

export function closeImagePreview(): void {
    imagePreviewState.value = null;
}
