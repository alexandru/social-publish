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
        assertEquals("", state.content)
        assertEquals("", state.link)
        assertEquals(setOf("rss"), state.targets)
        assertTrue(state.images.isEmpty())
        assertFalse(state.isSubmitting)
        assertFalse(state.isProcessing)
        assertFalse(state.isFormDisabled)
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
        assertTrue(withMastodon.targets.contains("rss"))

        // Remove rss
        val withoutRss = withMastodon.toggleTarget("rss")
        assertFalse(withoutRss.targets.contains("rss"))
        assertTrue(withoutRss.targets.contains("mastodon"))

        // Re-add rss
        val withRssAgain = withoutRss.toggleTarget("rss")
        assertTrue(withRssAgain.targets.contains("rss"))
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
    fun testUpdateImage() {
        val image1 = SelectedImage(id = 1, file = null, altText = "Original")
        val state = PublishFormState().addImage(image1)

        val image1Updated = SelectedImage(id = 1, file = null, altText = "Updated")
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
        assertEquals(setOf("rss"), reset.targets)
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
        val state = PublishFormState().updateContent("Hello").updateLink("https://example.com")
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
        assertEquals(2000, state.maxCharacters)
    }

    @Test
    fun testMaxCharactersWithSingleTarget() {
        val state = PublishFormState().toggleTarget("mastodon")
        assertEquals(500, state.maxCharacters)
    }

    @Test
    fun testMaxCharactersWithMastodonAndBluesky() {
        val state = PublishFormState().toggleTarget("mastodon").toggleTarget("bluesky")
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
        val state = PublishFormState().updateContent("Hello, World!").toggleTarget("mastodon")
        assertEquals(500, state.maxCharacters)
        assertEquals(487, state.charactersRemaining) // 500 - 13
    }
}
