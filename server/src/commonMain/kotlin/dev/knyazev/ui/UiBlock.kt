package dev.knyazev.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Домен-агностичный UI-примитив, эмитируемый агентом в SSE-событии `block`
 * (ADR-024). Сериализация полиморфная: во фронт уходит JSON с дискриминатором
 * `type`, совпадающим с `@SerialName` конкретного подкласса.
 */
@Serializable
sealed class UiBlock

@Serializable
@SerialName("image")
data class ImageBlock(
    val url: String,
    val caption: String? = null,
    val alt: String? = null,
    val link: String? = null,
) : UiBlock()

@Serializable
@SerialName("image_gallery")
data class ImageGalleryBlock(
    val images: List<GalleryImage>,
) : UiBlock() {
    @Serializable
    data class GalleryImage(val url: String, val caption: String? = null)
}

@Serializable
@SerialName("link_list")
data class LinkListBlock(
    val items: List<LinkItem>,
) : UiBlock() {
    @Serializable
    data class LinkItem(val url: String, val title: String, val description: String? = null)
}

@Serializable
@SerialName("text_with_image")
data class TextWithImageBlock(
    /** Текст в markdown. Рендерится фронтом через marked. */
    val text: String,
    val image: ImageRef,
    val imagePosition: ImagePosition = ImagePosition.LEFT,
) : UiBlock() {
    @Serializable
    data class ImageRef(val url: String, val alt: String? = null, val caption: String? = null)

    @Serializable
    enum class ImagePosition {
        @SerialName("left") LEFT,
        @SerialName("right") RIGHT,
    }
}
