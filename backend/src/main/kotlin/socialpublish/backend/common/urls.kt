package socialpublish.backend.common

import java.net.URI

data class ParsedUrl(val scheme: String, val host: String, val port: Int?) {
    fun isLocal(): Boolean {
        // Consider common loopback addresses local, including IPv6 loopback
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return h == "localhost" ||
            h == "127.0.0.1" ||
            h == "::1" ||
            h == "0:0:0:0:0:0:0:1"
    }
}

fun parseUrl(url: String): ParsedUrl? =
    try {
        var uri = URI(url)
        if (uri.scheme == null) {
            uri = URI("https://$url")
        }
        val scheme = uri.scheme
        val host = uri.host
        val port = if (uri.port > 0) uri.port else null
        if (scheme == null || host == null) {
            return null
        }
        return ParsedUrl(scheme, host, port)
    } catch (e: Throwable) {
        rethrowIfFatalOrCancelled(e)
        logger.error("Failed to parse URL: $url", e)
        null
    }

private val logger by loggerFactory()
