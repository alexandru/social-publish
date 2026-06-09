package socialpublish.backend.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewPostRequestMessageTest {

    @Test
    fun `isPublishable is true when content is non-blank`() {
        val message = NewPostRequestMessage(content = "Hello")
        assertTrue(message.isPublishable)
    }

    @Test
    fun `isPublishable is true when link is non-blank and content is blank`() {
        val message =
            NewPostRequestMessage(content = "", link = "https://example.com")
        assertTrue(message.isPublishable)
    }

    @Test
    fun `isPublishable is true when at least one image is present`() {
        val message =
            NewPostRequestMessage(content = "", images = listOf("image-1"))
        assertTrue(message.isPublishable)
    }

    @Test
    fun `isPublishable is false when content is blank and link is null`() {
        val message = NewPostRequestMessage(content = "")
        assertFalse(message.isPublishable)
    }

    @Test
    fun `isPublishable is false when everything is blank or empty`() {
        val message =
            NewPostRequestMessage(
                content = "   ",
                link = "   ",
                images = emptyList(),
            )
        assertFalse(message.isPublishable)
    }

    @Test
    fun `isPublishable is false when images list contains only blank strings`() {
        val message =
            NewPostRequestMessage(
                content = "",
                link = null,
                images = listOf("", "  "),
            )
        assertFalse(message.isPublishable)
    }

    @Test
    fun `buildPostText returns link only when content is blank`() {
        val text =
            NewPostRequestMessage.buildPostText(
                content = "",
                link = "https://example.com",
            )
        assertEquals("https://example.com", text)
    }

    @Test
    fun `buildPostText returns content only when link is null`() {
        val text =
            NewPostRequestMessage.buildPostText(
                content = "Hello world",
                link = null,
            )
        assertEquals("Hello world", text)
    }

    @Test
    fun `buildPostText joins content and link with two newlines`() {
        val text =
            NewPostRequestMessage.buildPostText(
                content = "Hello",
                link = "https://example.com",
            )
        assertEquals("Hello\n\nhttps://example.com", text)
    }

    @Test
    fun `buildPostText trims a leading newline in the content`() {
        val text =
            NewPostRequestMessage.buildPostText(
                content = "\n\nHello",
                link = "https://example.com",
            )
        assertEquals("Hello\n\nhttps://example.com", text)
    }

    @Test
    fun `buildPostText returns empty string when content and link are blank`() {
        val text =
            NewPostRequestMessage.buildPostText(content = "", link = null)
        assertEquals("", text)
    }
}
