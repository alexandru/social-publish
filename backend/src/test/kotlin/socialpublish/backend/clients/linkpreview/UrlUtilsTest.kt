package socialpublish.backend.clients.linkpreview

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlUtilsTest {

    @Test
    fun `resolves absolute HTTP URLs unchanged`() {
        val result = resolveImageUrl("http://example.com/image.jpg", "https://base.com")
        assertEquals("http://example.com/image.jpg", result)
    }

    @Test
    fun `resolves absolute HTTPS URLs unchanged`() {
        val result = resolveImageUrl("https://example.com/image.jpg", "https://base.com")
        assertEquals("https://example.com/image.jpg", result)
    }

    @Test
    fun `resolves protocol-relative URLs with HTTPS base`() {
        val result = resolveImageUrl("//cdn.example.com/image.jpg", "https://base.com")
        assertEquals("https://cdn.example.com/image.jpg", result)
    }

    @Test
    fun `resolves protocol-relative URLs with HTTP base`() {
        val result = resolveImageUrl("//cdn.example.com/image.jpg", "http://base.com")
        assertEquals("http://cdn.example.com/image.jpg", result)
    }

    @Test
    fun `resolves root-relative URLs`() {
        val result = resolveImageUrl("/images/photo.jpg", "https://example.com/blog/post")
        assertEquals("https://example.com/images/photo.jpg", result)
    }

    @Test
    fun `resolves root-relative URLs with port`() {
        val result = resolveImageUrl("/images/photo.jpg", "https://example.com:8080/blog")
        assertEquals("https://example.com:8080/images/photo.jpg", result)
    }

    @Test
    fun `resolves root-relative URLs with standard HTTP port`() {
        val result = resolveImageUrl("/images/photo.jpg", "http://example.com:80/blog")
        assertEquals("http://example.com:80/images/photo.jpg", result)
    }

    @Test
    fun `resolves root-relative URLs with standard HTTPS port`() {
        val result = resolveImageUrl("/images/photo.jpg", "https://example.com:443/blog")
        assertEquals("https://example.com:443/images/photo.jpg", result)
    }

    @Test
    fun `resolves path-relative URLs from root`() {
        val result = resolveImageUrl("image.jpg", "https://example.com/")
        assertEquals("https://example.com/image.jpg", result)
    }

    @Test
    fun `resolves path-relative URLs from directory`() {
        val result = resolveImageUrl("image.jpg", "https://example.com/blog/")
        assertEquals("https://example.com/blog/image.jpg", result)
    }

    @Test
    fun `resolves path-relative URLs from file`() {
        val result = resolveImageUrl("image.jpg", "https://example.com/blog/post.html")
        assertEquals("https://example.com/blog/image.jpg", result)
    }

    @Test
    fun `resolves path-relative URLs with subdirectories`() {
        val result = resolveImageUrl("assets/image.jpg", "https://example.com/blog/post.html")
        assertEquals("https://example.com/blog/assets/image.jpg", result)
    }

    @Test
    fun `resolves path-relative URLs with parent directory navigation`() {
        val result =
            resolveImageUrl("../assets/image.jpg", "https://example.com/blog/posts/post.html")
        assertEquals("https://example.com/blog/assets/image.jpg", result)
    }

    @Test
    fun `resolves complex path-relative URLs`() {
        val result =
            resolveImageUrl(
                "../../images/photo.jpg",
                "https://example.com/blog/posts/2024/post.html",
            )
        assertEquals("https://example.com/blog/images/photo.jpg", result)
    }

    @Test
    fun `handles malformed base URL gracefully`() {
        val result = resolveImageUrl("image.jpg", "not-a-valid-url")
        assertEquals(null, result)
    }

    @Test
    fun `handles empty image URL`() {
        val result = resolveImageUrl("", "https://example.com")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `handles query parameters in image URL`() {
        val result = resolveImageUrl("/image.jpg?width=300&height=200", "https://example.com/blog")
        assertEquals("https://example.com/image.jpg?width=300&height=200", result)
    }

    @Test
    fun `handles fragments in image URL`() {
        val result = resolveImageUrl("/image.svg#icon", "https://example.com/blog")
        assertEquals("https://example.com/image.svg#icon", result)
    }

    @Test
    fun `resolves URLs with special characters`() {
        val result = resolveImageUrl("/images/café photo.jpg", "https://example.com")
        assertEquals("https://example.com/images/café photo.jpg", result)
    }

    @Test
    fun `handles base URL with path and query parameters`() {
        val result = resolveImageUrl("image.jpg", "https://example.com/blog/post?id=123&ref=home")
        assertEquals("https://example.com/blog/image.jpg", result)
    }

    @Test
    fun `handles base URL with fragment`() {
        val result = resolveImageUrl("image.jpg", "https://example.com/blog/post#section1")
        assertEquals("https://example.com/blog/image.jpg", result)
    }
}
