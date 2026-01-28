package socialpublish.frontend.pages

import socialpublish.frontend.components.SelectedImage
import socialpublish.frontend.components.buildPostText
import socialpublish.frontend.components.countCharactersWithLinks

/** Immutable state for the publish form. */
data class PublishFormState(
    val content: String = "",
    val link: String = "",
    val targets: Set<String> = setOf("rss"),
    val images: Map<Int, SelectedImage> = emptyMap(),
    val isSubmitting: Boolean = false,
    val isUploading: Boolean = false,
) {
    companion object {
        const val BLUESKY_LIMIT = 300
        const val MASTODON_LIMIT = 500
        const val TWITTER_LIMIT = 280
        const val LINKEDIN_LIMIT = 2000
    }

    val postText: String
        get() = buildPostText(content, link)

    val usedCharacters: Int
        get() = countCharactersWithLinks(postText)

    val blueskyRemaining: Int
        get() = BLUESKY_LIMIT - usedCharacters

    val mastodonRemaining: Int
        get() = MASTODON_LIMIT - usedCharacters

    val twitterRemaining: Int
        get() = TWITTER_LIMIT - usedCharacters

    val linkedinRemaining: Int
        get() = LINKEDIN_LIMIT - usedCharacters

    val maxCharacters: Int
        get() =
            sequenceOf(
                    if (targets.contains("bluesky")) BLUESKY_LIMIT else null,
                    if (targets.contains("mastodon")) MASTODON_LIMIT else null,
                    if (targets.contains("twitter")) TWITTER_LIMIT else null,
                    if (targets.contains("linkedin")) LINKEDIN_LIMIT else null,
                )
                .filterNotNull()
                .minOrNull() ?: LINKEDIN_LIMIT

    val charactersRemaining: Int
        get() = maxCharacters - usedCharacters

    fun reset(): PublishFormState = PublishFormState()

    fun updateContent(value: String): PublishFormState = copy(content = value)

    fun updateLink(value: String): PublishFormState = copy(link = value)

    fun toggleTarget(target: String): PublishFormState =
        copy(targets = if (targets.contains(target)) targets - target else targets + target)

    fun addImage(image: SelectedImage): PublishFormState =
        copy(images = images + (image.id to image))

    fun updateImage(image: SelectedImage): PublishFormState =
        copy(images = images + (image.id to image))

    fun removeImage(id: Int): PublishFormState = copy(images = images - id)

    fun setSubmitting(value: Boolean): PublishFormState = copy(isSubmitting = value)

    fun setUploading(value: Boolean): PublishFormState = copy(isUploading = value)
}
