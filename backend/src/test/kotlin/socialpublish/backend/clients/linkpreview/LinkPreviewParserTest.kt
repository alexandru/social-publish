package socialpublish.backend.clients.linkpreview

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class LinkPreviewParserTest {
    @Test
    fun `extracts Open Graph tags from HTML`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Test Article Title">
                <meta property="og:description" content="This is a test description">
                <meta property="og:url" content="https://example.com/article">
                <meta property="og:image" content="https://example.com/image.jpg">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview = LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com/article") }
        assertNotNull(preview)
        assertEquals("Test Article Title", preview.title)
        assertEquals("This is a test description", preview.description)
        assertEquals("https://example.com/article", preview.url)
        assertEquals("https://example.com/image.jpg", preview.image)
    }

    @Test
    fun `falls back to Twitter Cards when OG tags are missing`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="twitter:title" content="Twitter Title">
                <meta name="twitter:description" content="Twitter description">
                <meta name="twitter:url" content="https://example.com/twitter">
                <meta name="twitter:image" content="https://example.com/twitter-image.jpg">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview = LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com/twitter") }

        assertNotNull(preview)
        assertEquals("Twitter Title", preview.title)
        assertEquals("Twitter description", preview.description)
        assertEquals("https://example.com/twitter", preview.url)
        assertEquals("https://example.com/twitter-image.jpg", preview.image)
    }

    @Test
    fun `falls back to standard HTML tags when meta tags are missing`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Standard HTML Title</title>
                <meta name="description" content="Standard HTML description">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview =
            LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com/standard") }

        assertNotNull(preview)
        assertEquals("Standard HTML Title", preview.title)
        assertEquals("Standard HTML description", preview.description)
        assertEquals("https://example.com/standard", preview.url)
        assertNull(preview.image)
    }

    @Test
    fun `prioritizes Open Graph over Twitter Cards`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="OG Title">
                <meta name="twitter:title" content="Twitter Title">
                <meta property="og:description" content="OG description">
                <meta name="twitter:description" content="Twitter description">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview = LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com") }

        assertNotNull(preview)
        assertEquals("OG Title", preview.title)
        assertEquals("OG description", preview.description)
    }

    @Test
    fun `returns null when no usable metadata found`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
            </head>
            <body>No metadata here</body>
            </html>
            """
                .trimIndent()

        val preview = LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com") }

        assertNull(preview)
    }

    @Test
    fun `uses fallback URL when no URL in metadata`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Title Without URL</title>
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview =
            LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com/fallback-url") }

        assertNotNull(preview)
        assertEquals("https://example.com/fallback-url", preview.url)
    }

    @Test
    fun `handles Twitter image src variant`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="twitter:title" content="Twitter Title">
                <meta name="twitter:image:src" content="https://example.com/twitter-src.jpg">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview = LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com") }

        assertNotNull(preview)
        assertEquals("https://example.com/twitter-src.jpg", preview.image)
    }

    @Test
    fun `handles relative image URLs by resolving them to absolute URLs`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Relative Image Test</title>
                <meta property="og:image" content="/images/relative.jpg">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview = LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com") }

        assertNotNull(preview)
        assertEquals("https://example.com/images/relative.jpg", preview.image)
    }

    @Test
    fun `handles absolute image URLs by keeping them unchanged`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Absolute Image Test</title>
                <meta property="og:image" content="https://cdn.example.com/images/absolute.jpg">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview = LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com") }

        assertNotNull(preview)
        assertEquals("https://cdn.example.com/images/absolute.jpg", preview.image)
    }

    @Test
    fun `handles protocol-relative image URLs`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Protocol Relative Image Test</title>
                <meta property="og:image" content="//cdn.example.com/images/protocol-relative.jpg">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview = LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com") }

        assertNotNull(preview)
        assertEquals("https://cdn.example.com/images/protocol-relative.jpg", preview.image)
    }

    @Test
    fun `handles path-relative image URLs`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Path Relative Image Test</title>
                <meta property="og:image" content="images/path-relative.jpg">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview =
            LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com/blog/post") }

        assertNotNull(preview)
        assertEquals("https://example.com/blog/images/path-relative.jpg", preview.image)
    }

    @Test
    fun `handles malformed base URL by returning preview without image`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Malformed Base URL Test</title>
                <meta property="og:image" content="/images/relative.jpg">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview = LinkPreviewParser.scoped { it.parseHtml(html, "not-a-valid-url") }

        assertNotNull(preview)
        assertEquals("Malformed Base URL Test", preview.title)
        assertNull(preview.image) // Should be null due to malformed base URL
    }

    @Test
    fun `fetches YouTube video using oembed API for youtube com URL`() = runTest {
        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                "https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D5l2wMgm7ZOk&format=json&maxwidth=1280&maxheight=720" ->
                    respond(
                        content =
                            ByteReadChannel(
                                """
                                {
                                    "title": "Why Black Boxes are so Hard to Reuse",
                                    "author_name": "Computer History Museum",
                                    "type": "video",
                                    "thumbnail_url": "https://i.ytimg.com/vi/5l2wMgm7ZOk/hqdefault.jpg"
                                }
                                """
                                    .trimIndent()
                            ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                else -> respond(content = "", status = HttpStatusCode.NotFound)
            }
        }

        val parser = LinkPreviewParser(HttpClient(mockEngine))
        val preview = parser.fetchPreview("https://www.youtube.com/watch?v=5l2wMgm7ZOk")

        assertNotNull(preview)
        assertEquals("Why Black Boxes are so Hard to Reuse", preview.title)
        assertEquals("Computer History Museum", preview.description)
        assertEquals("https://www.youtube.com/watch?v=5l2wMgm7ZOk", preview.url)
        assertEquals("https://i.ytimg.com/vi/5l2wMgm7ZOk/hqdefault.jpg", preview.image)
    }

    @Test
    fun `fetches YouTube video using oembed API for youtu be URL`() = runTest {
        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                "https://www.youtube.com/oembed?url=https%3A%2F%2Fyoutu.be%2FY3N9qlPZBc0&format=json&maxwidth=1280&maxheight=720" ->
                    respond(
                        content =
                            ByteReadChannel(
                                """
                                {
                                    "title": "OpenAI is Broke",
                                    "author_name": "Vanessa Wingårdh",
                                    "type": "video",
                                    "thumbnail_url": "https://i.ytimg.com/vi/Y3N9qlPZBc0/hqdefault.jpg"
                                }
                                """
                                    .trimIndent()
                            ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                else -> respond(content = "", status = HttpStatusCode.NotFound)
            }
        }

        val parser = LinkPreviewParser(HttpClient(mockEngine))
        val preview = parser.fetchPreview("https://youtu.be/Y3N9qlPZBc0")

        assertNotNull(preview)
        assertEquals("OpenAI is Broke", preview.title)
        assertEquals("Vanessa Wingårdh", preview.description)
        assertEquals("https://youtu.be/Y3N9qlPZBc0", preview.url)
        assertEquals("https://i.ytimg.com/vi/Y3N9qlPZBc0/hqdefault.jpg", preview.image)
    }

    @Test
    fun `returns null when YouTube oembed API returns error`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(content = "", status = HttpStatusCode.NotFound)
        }

        val parser = LinkPreviewParser(HttpClient(mockEngine))
        val preview = parser.fetchPreview("https://www.youtube.com/watch?v=invalid")

        assertNull(preview)
    }

    @Test
    fun `returns null when YouTube oembed API returns invalid JSON`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel("invalid json"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val parser = LinkPreviewParser(HttpClient(mockEngine))
        val preview = parser.fetchPreview("https://www.youtube.com/watch?v=123")

        assertNull(preview)
    }

    @Test
    fun `resolves relative og url to absolute URL`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Test Article">
                <meta property="og:description" content="Test description">
                <meta property="og:url" content="/funfix/tasks/releases/tag/v0.4.0">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview =
            LinkPreviewParser.scoped {
                it.parseHtml(html, "https://github.com/funfix/tasks/releases/tag/v0.4.0")
            }

        assertNotNull(preview)
        assertEquals("Test Article", preview.title)
        assertEquals("Test description", preview.description)
        assertEquals(
            "https://github.com/funfix/tasks/releases/tag/v0.4.0",
            preview.url,
        ) // Should be absolute, not relative
    }

    @Test
    fun `resolves protocol-relative og url to absolute URL`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Test Article">
                <meta property="og:url" content="//example.com/path/to/article">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview =
            LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com/original/path") }

        assertNotNull(preview)
        assertEquals("https://example.com/path/to/article", preview.url)
    }

    @Test
    fun `keeps absolute og url unchanged`() = runTest {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Test Article">
                <meta property="og:url" content="https://canonical.example.com/article">
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview =
            LinkPreviewParser.scoped { it.parseHtml(html, "https://example.com/original/path") }

        assertNotNull(preview)
        assertEquals("https://canonical.example.com/article", preview.url)
    }
}
