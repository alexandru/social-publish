package socialpublish.backend.linkpreview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import socialpublish.backend.clients.linkpreview.LinkPreviewParser

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
}
