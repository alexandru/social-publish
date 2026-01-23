package socialpublish.backend.linkpreview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import socialpublish.backend.clients.linkpreview.isYouTubeUrl
import socialpublish.backend.clients.linkpreview.parseYouTubeOEmbedResponse

class YouTubeOEmbedTest {

    @Test
    fun `detects youtube com URLs`() {
        assertTrue(isYouTubeUrl("https://www.youtube.com/watch?v=5l2wMgm7ZOk"))
        assertTrue(isYouTubeUrl("http://www.youtube.com/watch?v=5l2wMgm7ZOk"))
        assertTrue(isYouTubeUrl("https://youtube.com/watch?v=5l2wMgm7ZOk"))
        assertTrue(isYouTubeUrl("http://youtube.com/watch?v=5l2wMgm7ZOk"))
        assertTrue(isYouTubeUrl("https://m.youtube.com/watch?v=5l2wMgm7ZOk"))
    }

    @Test
    fun `detects youtu be URLs`() {
        assertTrue(isYouTubeUrl("https://youtu.be/5l2wMgm7ZOk"))
        assertTrue(isYouTubeUrl("http://youtu.be/5l2wMgm7ZOk"))
        assertTrue(isYouTubeUrl("https://www.youtu.be/5l2wMgm7ZOk"))
    }

    @Test
    fun `rejects non-YouTube URLs`() {
        assertFalse(isYouTubeUrl("https://example.com"))
        assertFalse(isYouTubeUrl("https://vimeo.com/123456"))
        assertFalse(isYouTubeUrl("https://youtu.be.evil.com/watch?v=123"))
        assertFalse(isYouTubeUrl("https://notyoutube.com/watch?v=123"))
    }

    @Test
    fun `parses valid YouTube oembed response`() = runTest {
        val json =
            """
            {
                "title": "Why Black Boxes are so Hard to Reuse, lecture by Gregor Kiczales",
                "author_name": "Computer History Museum",
                "author_url": "https://www.youtube.com/@ComputerHistory",
                "type": "video",
                "height": 150,
                "width": 200,
                "version": "1.0",
                "provider_name": "YouTube",
                "provider_url": "https://www.youtube.com/",
                "thumbnail_height": 360,
                "thumbnail_width": 480,
                "thumbnail_url": "https://i.ytimg.com/vi/5l2wMgm7ZOk/hqdefault.jpg",
                "html": "<iframe width=\"200\" height=\"150\" src=\"https://www.youtube.com/embed/5l2wMgm7ZOk?feature=oembed\" frameborder=\"0\" allow=\"accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share\" referrerpolicy=\"strict-origin-when-cross-origin\" allowfullscreen title=\"Why Black Boxes are so Hard to Reuse, lecture by Gregor Kiczales\"></iframe>"
            }
            """
                .trimIndent()

        val preview =
            parseYouTubeOEmbedResponse(json, "https://www.youtube.com/watch?v=5l2wMgm7ZOk")

        assertNotNull(preview)
        assertEquals(
            "Why Black Boxes are so Hard to Reuse, lecture by Gregor Kiczales",
            preview.title,
        )
        assertEquals("Computer History Museum", preview.description)
        assertEquals("https://www.youtube.com/watch?v=5l2wMgm7ZOk", preview.url)
        assertEquals("https://i.ytimg.com/vi/5l2wMgm7ZOk/hqdefault.jpg", preview.image)
    }

    @Test
    fun `parses YouTube oembed response with author as description`() = runTest {
        val json =
            """
            {
                "title": "OpenAI is Broke… and so is everyone else",
                "author_name": "Vanessa Wingårdh",
                "author_url": "https://www.youtube.com/@VanessaWing%C3%A5rdh",
                "type": "video",
                "height": 113,
                "width": 200,
                "version": "1.0",
                "provider_name": "YouTube",
                "provider_url": "https://www.youtube.com/",
                "thumbnail_height": 360,
                "thumbnail_width": 480,
                "thumbnail_url": "https://i.ytimg.com/vi/Y3N9qlPZBc0/hqdefault.jpg",
                "html": "<iframe width=\"200\" height=\"113\" src=\"https://www.youtube.com/embed/Y3N9qlPZBc0?feature=oembed\" frameborder=\"0\" allow=\"accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share\" referrerpolicy=\"strict-origin-when-cross-origin\" allowfullscreen title=\"OpenAI is Broke… and so is everyone else\"></iframe>"
            }
            """
                .trimIndent()

        val preview =
            parseYouTubeOEmbedResponse(json, "https://www.youtube.com/watch?v=Y3N9qlPZBc0")

        assertNotNull(preview)
        assertEquals("OpenAI is Broke… and so is everyone else", preview.title)
        assertEquals("Vanessa Wingårdh", preview.description)
        assertEquals("https://www.youtube.com/watch?v=Y3N9qlPZBc0", preview.url)
        assertEquals("https://i.ytimg.com/vi/Y3N9qlPZBc0/hqdefault.jpg", preview.image)
    }

    @Test
    fun `returns null for invalid JSON`() = runTest {
        val json = "not valid json"
        val preview = parseYouTubeOEmbedResponse(json, "https://www.youtube.com/watch?v=123")
        assertNull(preview)
    }

    @Test
    fun `returns null for incomplete oembed response`() = runTest {
        val json = """{"type": "video"}"""
        val preview = parseYouTubeOEmbedResponse(json, "https://www.youtube.com/watch?v=123")
        assertNull(preview)
    }

    @Test
    fun `returns null for empty response`() = runTest {
        val json = "{}"
        val preview = parseYouTubeOEmbedResponse(json, "https://www.youtube.com/watch?v=123")
        assertNull(preview)
    }
}
