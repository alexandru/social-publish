package socialpublish.frontend.pages

import socialpublish.frontend.components.SelectedImage
import socialpublish.frontend.components.buildPostText
import socialpublish.frontend.components.countCharactersWithLinks

/** Immutable state for the publish form. */
data class PublishFormState(
    val content: String = "",
    val link: String = "",
    val targets: Set<String> = setOf("rss"),
    val cleanupHtml: Boolean = false,
    val images: Map<Int, SelectedImage> = emptyMap(),
    val isSubmitting: Boolean = false,
) {
    val postText: String
        get() = buildPostText(content, link)

    val usedCharacters: Int
        get() = countCharactersWithLinks(postText)

    val blueskyRemaining: Int
        get() = 300 - usedCharacters

    val mastodonRemaining: Int
        get() = 500 - usedCharacters

    val twitterRemaining: Int
        get() = 280 - usedCharacters

    val linkedinRemaining: Int
        get() = 2000 - usedCharacters

    fun reset(): PublishFormState = PublishFormState()

    fun updateContent(value: String): PublishFormState = copy(content = value)

    fun updateLink(value: String): PublishFormState = copy(link = value)

    fun toggleTarget(target: String): PublishFormState =
        copy(targets = if (targets.contains(target)) targets - target else targets + target)

    fun updateCleanupHtml(value: Boolean): PublishFormState = copy(cleanupHtml = value)

    fun addImage(image: SelectedImage): PublishFormState =
        copy(images = images + (image.id to image))

    fun updateImage(image: SelectedImage): PublishFormState =
        copy(images = images + (image.id to image))

    fun removeImage(id: Int): PublishFormState = copy(images = images - id)

    fun setSubmitting(value: Boolean): PublishFormState = copy(isSubmitting = value)
}
