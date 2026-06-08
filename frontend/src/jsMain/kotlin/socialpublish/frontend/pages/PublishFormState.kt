package socialpublish.frontend.pages

import socialpublish.frontend.components.SelectedImage
import socialpublish.frontend.components.buildPostText
import socialpublish.frontend.components.countCharactersWithLinks

data class SubmitError(val message: String)

private data class PublishTargetSpec(
    val serialName: String,
    val displayName: String,
    val perMessageCharacterLimit: Int? = null,
    val threadCharacterLimit: Int? = null,
    val threadImageLimit: Int? = null,
) {
    fun unsupportedReason(state: PublishFormState): String? {
        perMessageCharacterLimit?.let { limit ->
            state.messages.forEachIndexed { index, message ->
                if (message.usedCharacters > limit) {
                    return "Post ${index + 1} exceeds $displayName's $limit character limit."
                }
            }
        }

        threadCharacterLimit?.let { limit ->
            if (state.threadUsedCharacters > limit) {
                return "$displayName supports at most $limit characters across the thread."
            }
        }

        threadImageLimit?.let { limit ->
            if (state.threadImageCount > limit) {
                return "$displayName supports at most $limit images across the thread."
            }
        }

        return null
    }
}

private val PublishTargetSpecs =
    listOf(
        PublishTargetSpec(
            serialName = "feed",
            displayName = "Feed",
            perMessageCharacterLimit = PublishFormState.FEED_LIMIT,
        ),
        PublishTargetSpec(
            serialName = "bluesky",
            displayName = "Bluesky",
            perMessageCharacterLimit = PublishFormState.BLUESKY_LIMIT,
        ),
        PublishTargetSpec(
            serialName = "mastodon",
            displayName = "Mastodon",
            perMessageCharacterLimit = PublishFormState.MASTODON_LIMIT,
        ),
        PublishTargetSpec(
            serialName = "twitter",
            displayName = "Twitter",
            perMessageCharacterLimit = PublishFormState.TWITTER_LIMIT,
        ),
        PublishTargetSpec(
            serialName = "linkedin",
            displayName = "LinkedIn",
            threadCharacterLimit = PublishFormState.LINKEDIN_LIMIT,
            threadImageLimit = PublishFormState.LINKEDIN_MAX_IMAGES,
        ),
    )

private fun targetSpec(target: String): PublishTargetSpec? =
    PublishTargetSpecs.firstOrNull {
        it.serialName == target
    }

data class PublishMessageState(
    val id: Int,
    val content: String = "",
    val link: String = "",
    val images: Map<Int, SelectedImage> = emptyMap(),
) {
    val postText: String
        get() = buildPostText(content, link)

    val usedCharacters: Int
        get() = countCharactersWithLinks(postText)

    fun supportedTargets(): Set<String> =
        PublishTargetSpecs.filter { spec ->
                spec.perMessageCharacterLimit?.let { usedCharacters <= it } !=
                    false
            }
            .map { it.serialName }
            .toSet()
}

/** Immutable state for the publish form. */
data class PublishFormState(
    val messages: List<PublishMessageState> =
        listOf(PublishMessageState(id = 1)),
    val language: String? = null,
    val targets: Set<String> = setOf("feed"),
    val isSubmitting: Boolean = false,
    val isProcessing: Boolean = false,
) {
    companion object {
        const val FEED_LIMIT = 1000
        const val BLUESKY_LIMIT = 300
        const val MASTODON_LIMIT = 500
        const val TWITTER_LIMIT = 280
        const val LINKEDIN_LIMIT = 2000
        const val LINKEDIN_MAX_IMAGES = 4
    }

    private val firstMessage: PublishMessageState
        get() = messages.first()

    val content: String
        get() = firstMessage.content

    val link: String
        get() = firstMessage.link

    val images: Map<Int, SelectedImage>
        get() = firstMessage.images

    val postText: String
        get() = firstMessage.postText

    val usedCharacters: Int
        get() = firstMessage.usedCharacters

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
                    if (targets.contains("feed")) FEED_LIMIT else null,
                    if (targets.contains("bluesky")) BLUESKY_LIMIT else null,
                    if (targets.contains("mastodon")) MASTODON_LIMIT else null,
                    if (targets.contains("twitter")) TWITTER_LIMIT else null,
                )
                .filterNotNull()
                .minOrNull() ?: LINKEDIN_LIMIT

    val charactersRemaining: Int
        get() = maxCharacters - usedCharacters

    val threadText: String
        get() = messages.joinToString("\n\n") { it.postText }

    val linkedinThreadText: String
        get() = threadText

    val threadUsedCharacters: Int
        get() = countCharactersWithLinks(threadText)

    val linkedinUsedCharacters: Int
        get() = threadUsedCharacters

    val linkedinCharactersRemaining: Int
        get() = LINKEDIN_LIMIT - linkedinUsedCharacters

    val linkedinImageCount: Int
        get() = threadImageCount

    val threadImageCount: Int
        get() = messages.sumOf { it.images.size }

    val hasTooManyLinkedInImages: Boolean
        get() =
            targets.contains("linkedin") &&
                linkedinImageCount > LINKEDIN_MAX_IMAGES

    val hasLinkedInCharacterOverflow: Boolean
        get() = targets.contains("linkedin") && linkedinCharactersRemaining < 0

    val targetGuidanceWarnings: List<String>
        get() =
            targets.toList().mapNotNull { target ->
                val spec = targetSpec(target) ?: return@mapNotNull null
                spec.unsupportedReason(this)?.let { "${spec.displayName}: $it" }
            }

    val unavailableTargetWarnings: List<String>
        get() =
            PublishTargetSpecs.filterNot { it.serialName in targets }
                .mapNotNull { spec ->
                    spec.unsupportedReason(this)?.let {
                        "${spec.displayName} is unavailable: $it"
                    }
                }

    fun targetDisabledReason(target: String): String? =
        targetSpec(target)?.unsupportedReason(this)

    fun isTargetSupported(target: String): Boolean =
        targetDisabledReason(target) == null

    fun maxCharactersFor(message: PublishMessageState): Int =
        sequenceOf(
                selectedPerMessageCharacterLimit(),
                linkedinCharacterBudgetBefore(message),
            )
            .filterNotNull()
            .minOrNull() ?: LINKEDIN_LIMIT

    fun charactersRemainingFor(message: PublishMessageState): Int =
        maxCharactersFor(message) - message.usedCharacters

    private fun selectedPerMessageCharacterLimit(): Int? =
        targets
            .mapNotNull { targetSpec(it)?.perMessageCharacterLimit }
            .minOrNull()

    private fun linkedinCharacterBudgetBefore(
        message: PublishMessageState
    ): Int? {
        if (!targets.contains("linkedin")) {
            return null
        }

        val messageIndex = messages.indexOfFirst { it.id == message.id }
        if (messageIndex < 0) {
            return LINKEDIN_LIMIT
        }

        val priorText =
            messages.take(messageIndex).joinToString("\n\n") { it.postText }
        val priorCharacters =
            if (messageIndex == 0) 0
            else countCharactersWithLinks(priorText) + 2

        return LINKEDIN_LIMIT - priorCharacters
    }

    fun validateForSubmit(): List<SubmitError> = buildList {
        val blankMessageIndex = messages.indexOfFirst { it.content.isBlank() }
        if (blankMessageIndex >= 0) {
            add(
                SubmitError(
                    "Content is required for post ${blankMessageIndex + 1}!"
                )
            )
        }
        if (targets.isEmpty()) {
            add(SubmitError("At least one publication target is required!"))
        }
    }

    fun canAddImageTo(message: PublishMessageState): Boolean =
        message.images.size < 4 &&
            (!targets.contains("linkedin") ||
                threadImageCount < LINKEDIN_MAX_IMAGES)

    fun reset(): PublishFormState = PublishFormState()

    fun updateContent(value: String): PublishFormState =
        updateMessageContent(firstMessage.id, value)

    fun updateLink(value: String): PublishFormState =
        updateMessageLink(firstMessage.id, value)

    fun updateLanguage(value: String?): PublishFormState =
        copy(language = value)

    fun toggleTarget(target: String): PublishFormState =
        copy(
            targets =
                if (targets.contains(target)) targets - target
                else targets + target
        )

    fun addMessage(): PublishFormState {
        val nextId = (messages.maxOfOrNull { it.id } ?: 0) + 1
        return copy(messages = messages + PublishMessageState(id = nextId))
    }

    fun removeMessage(messageId: Int): PublishFormState =
        if (messages.size == 1) this
        else copy(messages = messages.filterNot { it.id == messageId })

    fun updateMessageContent(messageId: Int, value: String): PublishFormState =
        updateMessage(messageId) { it.copy(content = value) }

    fun updateMessageLink(messageId: Int, value: String): PublishFormState =
        updateMessage(messageId) { it.copy(link = value) }

    fun addImage(image: SelectedImage): PublishFormState =
        addImage(firstMessage.id, image)

    fun addImage(messageId: Int, image: SelectedImage): PublishFormState =
        updateMessage(messageId) {
            it.copy(images = it.images + (image.id to image))
        }

    fun updateImage(image: SelectedImage): PublishFormState =
        updateImage(firstMessage.id, image)

    fun updateImage(messageId: Int, image: SelectedImage): PublishFormState =
        updateMessage(messageId) {
            it.copy(images = it.images + (image.id to image))
        }

    fun removeImage(id: Int): PublishFormState =
        removeImage(firstMessage.id, id)

    fun removeImage(messageId: Int, imageId: Int): PublishFormState =
        updateMessage(messageId) { it.copy(images = it.images - imageId) }

    private fun updateMessage(
        messageId: Int,
        transform: (PublishMessageState) -> PublishMessageState,
    ): PublishFormState =
        copy(
            messages =
                messages.map { message ->
                    if (message.id == messageId) transform(message) else message
                }
        )

    fun setSubmitting(value: Boolean): PublishFormState =
        copy(isSubmitting = value)

    fun setProcessing(value: Boolean): PublishFormState =
        copy(isProcessing = value)

    val isFormDisabled: Boolean
        get() =
            isSubmitting ||
                isProcessing ||
                messages.any { message ->
                    message.images.values.any { it.isGeneratingAltText }
                }
}
