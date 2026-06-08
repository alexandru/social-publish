package socialpublish.frontend.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import socialpublish.frontend.components.SelectedImage

class PublishFormStateTest {

    @Test
    fun testDefaultState() {
        val state = PublishFormState()
        assertEquals(1, state.messages.size)
        assertEquals(1, state.messages.first().id)
        assertEquals("", state.content)
        assertEquals("", state.link)
        assertEquals(setOf("feed"), state.targets)
        assertTrue(state.images.isEmpty())
        assertFalse(state.isSubmitting)
        assertFalse(state.isProcessing)
        assertFalse(state.isFormDisabled)
    }

    @Test
    fun testAddMessage() {
        val state = PublishFormState().addMessage()

        assertEquals(2, state.messages.size)
        assertEquals(listOf(1, 2), state.messages.map { it.id })
    }

    @Test
    fun testRemoveMessageKeepsAtLeastOneMessage() {
        val state = PublishFormState()

        assertEquals(state, state.removeMessage(1))

        val twoMessages = state.addMessage()
        val updated = twoMessages.removeMessage(1)

        assertEquals(1, updated.messages.size)
        assertEquals(2, updated.messages.first().id)
    }

    @Test
    fun testUpdateMessageFields() {
        val state = PublishFormState().addMessage()
        val updated =
            state
                .updateMessageContent(2, "Follow-up")
                .updateMessageLink(2, "https://example.com/follow-up")

        assertEquals("", updated.messages.first { it.id == 1 }.content)
        assertEquals("Follow-up", updated.messages.first { it.id == 2 }.content)
        assertEquals(
            "https://example.com/follow-up",
            updated.messages.first { it.id == 2 }.link,
        )
    }

    @Test
    fun testUpdateContent() {
        val state = PublishFormState()
        val updated = state.updateContent("Hello, World!")
        assertEquals("Hello, World!", updated.content)
        assertEquals("", state.content) // Original unchanged
    }

    @Test
    fun testUpdateLink() {
        val state = PublishFormState()
        val updated = state.updateLink("https://example.com")
        assertEquals("https://example.com", updated.link)
    }

    @Test
    fun testToggleTarget() {
        val state = PublishFormState()
        // Add mastodon
        val withMastodon = state.toggleTarget("mastodon")
        assertTrue(withMastodon.targets.contains("mastodon"))
        assertTrue(withMastodon.targets.contains("feed"))

        // Remove feed
        val withoutFeed = withMastodon.toggleTarget("feed")
        assertFalse(withoutFeed.targets.contains("feed"))
        assertTrue(withoutFeed.targets.contains("mastodon"))

        // Re-add feed
        val withFeedAgain = withoutFeed.toggleTarget("feed")
        assertTrue(withFeedAgain.targets.contains("feed"))
    }

    @Test
    fun testAddImage() {
        val state = PublishFormState()
        val image = SelectedImage(id = 1, file = null, altText = "Test")
        val updated = state.addImage(image)
        assertEquals(1, updated.images.size)
        assertEquals(image, updated.images[1])
    }

    @Test
    fun testAddImageToSpecificMessage() {
        val state = PublishFormState().addMessage()
        val image = SelectedImage(id = 1, file = null, altText = "Test")

        val updated = state.addImage(2, image)

        assertTrue(updated.messages.first { it.id == 1 }.images.isEmpty())
        assertEquals(image, updated.messages.first { it.id == 2 }.images[1])
    }

    @Test
    fun testUpdateImage() {
        val image1 = SelectedImage(id = 1, file = null, altText = "Original")
        val state = PublishFormState().addImage(image1)

        val image1Updated =
            SelectedImage(id = 1, file = null, altText = "Updated")
        val updated = state.updateImage(image1Updated)
        assertEquals("Updated", updated.images[1]?.altText)
    }

    @Test
    fun testRemoveImage() {
        val image1 = SelectedImage(id = 1, file = null)
        val image2 = SelectedImage(id = 2, file = null)
        val state = PublishFormState().addImage(image1).addImage(image2)

        val updated = state.removeImage(1)
        assertEquals(1, updated.images.size)
        assertEquals(null, updated.images[1])
        assertEquals(image2, updated.images[2])
    }

    @Test
    fun testSetSubmitting() {
        val state = PublishFormState()
        val submitting = state.setSubmitting(true)
        assertTrue(submitting.isSubmitting)
        assertTrue(submitting.isFormDisabled)
    }

    @Test
    fun testSetProcessing() {
        val state = PublishFormState()
        val processing = state.setProcessing(true)
        assertTrue(processing.isProcessing)
        assertTrue(processing.isFormDisabled)
    }

    @Test
    fun testIsFormDisabledWithGeneratingAltText() {
        val image = SelectedImage(id = 1, isGeneratingAltText = true)
        val state = PublishFormState().addImage(image)
        assertTrue(state.isFormDisabled)
    }

    @Test
    fun testIsFormDisabledWithGeneratingAltTextInAnyMessage() {
        val image = SelectedImage(id = 1, isGeneratingAltText = true)
        val state = PublishFormState().addMessage().addImage(2, image)

        assertTrue(state.isFormDisabled)
    }

    @Test
    fun testReset() {
        val state =
            PublishFormState()
                .updateContent("Test content")
                .updateLink("https://example.com")
                .toggleTarget("mastodon")
                .setSubmitting(true)
                .setProcessing(true)

        val reset = state.reset()
        assertEquals("", reset.content)
        assertEquals("", reset.link)
        assertEquals(setOf("feed"), reset.targets)
        assertFalse(reset.isSubmitting)
        assertFalse(reset.isProcessing)
        assertFalse(reset.isFormDisabled)
    }

    @Test
    fun testCharacterCountingBasic() {
        val state = PublishFormState().updateContent("Hello, World!")
        assertEquals("Hello, World!", state.postText)
        // 13 characters
        assertEquals(13, state.usedCharacters)
        assertEquals(287, state.blueskyRemaining) // 300 - 13
        assertEquals(487, state.mastodonRemaining) // 500 - 13
        assertEquals(267, state.twitterRemaining) // 280 - 13
        assertEquals(1987, state.linkedinRemaining) // 2000 - 13
    }

    @Test
    fun testCharacterCountingWithLink() {
        val state =
            PublishFormState()
                .updateContent("Check this out:")
                .updateLink("https://example.com/very/long/path")
        // "Check this out:\n\nhttps://example.com/very/long/path"
        // Links are counted as 25 characters regardless of length
        val contentChars = 15 // "Check this out:"
        val separatorChars = 2 // "\n\n"
        val linkChars = 25 // Standard link length
        val expected = contentChars + separatorChars + linkChars
        assertEquals(expected, state.usedCharacters)
    }

    @Test
    fun testPostTextCombinesContentAndLink() {
        val state =
            PublishFormState()
                .updateContent("Hello")
                .updateLink("https://example.com")
        assertEquals("Hello\n\nhttps://example.com", state.postText)
    }

    @Test
    fun testPostTextWithOnlyContent() {
        val state = PublishFormState().updateContent("Hello")
        assertEquals("Hello", state.postText)
    }

    @Test
    fun testMaxCharactersDefaultsTo2000() {
        val state = PublishFormState()
        assertEquals(1000, state.maxCharacters)
    }

    @Test
    fun testMaxCharactersWithSingleTarget() {
        val state = PublishFormState().toggleTarget("mastodon")
        assertEquals(500, state.maxCharacters)
    }

    @Test
    fun testMaxCharactersWithMastodonAndBluesky() {
        val state =
            PublishFormState().toggleTarget("mastodon").toggleTarget("bluesky")
        assertEquals(300, state.maxCharacters) // Minimum of 500 and 300
    }

    @Test
    fun testMaxCharactersWithAllTargets() {
        val state =
            PublishFormState()
                .toggleTarget("mastodon")
                .toggleTarget("bluesky")
                .toggleTarget("twitter")
                .toggleTarget("linkedin")
        assertEquals(280, state.maxCharacters) // Twitter has the lowest limit
    }

    @Test
    fun testCharactersRemainingBasedOnMaxCharacters() {
        val state =
            PublishFormState()
                .updateContent("Hello, World!")
                .toggleTarget("mastodon")
        assertEquals(500, state.maxCharacters)
        assertEquals(487, state.charactersRemaining) // 500 - 13
    }

    @Test
    fun testLinkedInAggregateCharactersAcrossThread() {
        val state =
            PublishFormState()
                .toggleTarget("linkedin")
                .updateContent("Root")
                .updateLink("https://example.com/root")
                .addMessage()
                .updateMessageContent(2, "Follow-up")
                .updateMessageLink(2, "https://example.com/follow-up")

        val expectedUsed =
            "Root".length + 2 + 25 + 2 + "Follow-up".length + 2 + 25
        assertEquals(expectedUsed, state.linkedinUsedCharacters)
        assertEquals(2000 - expectedUsed, state.linkedinCharactersRemaining)
    }

    @Test
    fun testPerMessageCounterReservesLinkedInBudgetUsedByPriorPosts() {
        val rootContent = "x".repeat(1700)
        val state =
            PublishFormState()
                .toggleTarget("linkedin")
                .updateContent(rootContent)
                .addMessage()

        val secondMessage = state.messages[1]

        assertEquals(298, state.maxCharactersFor(secondMessage))
        assertEquals(298, state.charactersRemainingFor(secondMessage))
    }

    @Test
    fun testPerMessageCounterUsesStrictestSelectedPerPostAndThreadLimit() {
        val state =
            PublishFormState()
                .toggleTarget("linkedin")
                .toggleTarget("bluesky")
                .updateContent("x".repeat(1800))
                .addMessage()
                .updateMessageContent(2, "Hello")

        val secondMessage = state.messages[1]

        assertEquals(198, state.maxCharactersFor(secondMessage))
        assertEquals(193, state.charactersRemainingFor(secondMessage))
    }

    @Test
    fun testLinkedInCountsImagesAcrossThread() {
        val state =
            PublishFormState()
                .toggleTarget("linkedin")
                .addImage(1, SelectedImage(id = 1))
                .addMessage()
                .addImage(2, SelectedImage(id = 1))
                .addImage(2, SelectedImage(id = 2))

        assertEquals(3, state.linkedinImageCount)
        assertFalse(state.hasTooManyLinkedInImages)
    }

    @Test
    fun testLinkedInUnavailableWhenThreadAlreadyHasTooManyImages() {
        val state =
            PublishFormState()
                .addImage(1, SelectedImage(id = 1))
                .addImage(1, SelectedImage(id = 2))
                .addMessage()
                .addImage(2, SelectedImage(id = 1))
                .addImage(2, SelectedImage(id = 2))
                .addImage(2, SelectedImage(id = 3))

        assertFalse(state.isTargetSupported("linkedin"))
        assertEquals(
            "LinkedIn supports at most 4 images across the thread.",
            state.targetDisabledReason("linkedin"),
        )
        assertEquals(
            listOf(
                "LinkedIn is unavailable: LinkedIn supports at most 4 images across the thread."
            ),
            state.unavailableTargetWarnings,
        )
    }

    @Test
    fun testLinkedInSelectedDisablesAddingImagesAfterThreadLimit() {
        val state =
            PublishFormState()
                .toggleTarget("linkedin")
                .addImage(1, SelectedImage(id = 1))
                .addImage(1, SelectedImage(id = 2))
                .addMessage()
                .addImage(2, SelectedImage(id = 1))
                .addImage(2, SelectedImage(id = 2))

        assertFalse(state.canAddImageTo(state.messages[0]))
        assertFalse(state.canAddImageTo(state.messages[1]))
    }

    @Test
    fun testTwitterUnavailableWhenAnyPostIsTooLong() {
        val state = PublishFormState().updateContent("x".repeat(281))

        assertFalse(state.isTargetSupported("twitter"))
        assertEquals(
            "Post 1 exceeds Twitter's 280 character limit.",
            state.targetDisabledReason("twitter"),
        )
    }

    @Test
    fun testSelectedUnsupportedTargetsProduceGuidanceWarnings() {
        val state =
            PublishFormState()
                .toggleTarget("twitter")
                .updateContent("x".repeat(281))

        assertEquals(
            listOf("Twitter: Post 1 exceeds Twitter's 280 character limit."),
            state.targetGuidanceWarnings,
        )
    }

    @Test
    fun testSubmitValidationOnlyCoversRequiredFrontendFields() {
        val blank = PublishFormState().updateContent(" ")
        val noTargets =
            PublishFormState().updateContent("Hello").toggleTarget("feed")
        val overLimit =
            PublishFormState()
                .toggleTarget("twitter")
                .updateContent("x".repeat(281))

        assertEquals(
            listOf(SubmitError("Content is required for post 1!")),
            blank.validateForSubmit(),
        )
        assertEquals(
            listOf(SubmitError("At least one publication target is required!")),
            noTargets.validateForSubmit(),
        )
        assertTrue(overLimit.validateForSubmit().isEmpty())
    }
}
