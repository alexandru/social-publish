package socialpublish.backend.common

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UrlsTest {

    @Test
    fun `parseUrl should parse full URL with scheme and host`() {
        val parsed = parseUrl("https://example.com/path")
        assertNotNull(parsed)
        assertEquals("https", parsed.scheme)
        assertEquals("example.com", parsed.host)
        assertNull(parsed.port)
    }

    @Test
    fun `parseUrl should return null for input that looks like scheme_host`() {
        // java.net.URI treats 'example.com:3000' as a scheme, not as host:port, so parser should
        // return null
        val parsed = parseUrl("example.com:3000")
        assertNull(parsed)
    }

    @Test
    fun `parseUrl should return null for invalid URLs`() {
        val parsed = parseUrl("not a valid url!")
        assertNull(parsed)
    }

    @Test
    fun `parseUrl should return null when URI has no host (file scheme)`() {
        val parsed = parseUrl("file:///tmp/somefile")
        assertNull(parsed)
    }

    @Test
    fun `ParsedUrl isLocal should detect localhost and 127_0_0_1`() {
        val localhost = parseUrl("http://localhost")
        assertNotNull(localhost)
        assertTrue(localhost.isLocal())

        val ip = parseUrl("http://127.0.0.1:8080")
        assertNotNull(ip)
        assertTrue(ip.isLocal())
    }

    @Test
    fun `ParsedUrl isLocal should be false for other hosts and detect IPv6 loopback as local`() {
        val remote = parseUrl("https://example.com")
        assertNotNull(remote)
        assertFalse(remote.isLocal())

        // IPv6 loopback should now be considered local
        val ipv6 = parseUrl("http://[::1]")
        assertNotNull(ipv6)
        assertTrue(ipv6.isLocal())
    }
}
