package socialpublish.backend.utils

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

data class ParsedUrl(val scheme: String, val host: String, val port: Int?) {
    fun isLocal(): Boolean {
        return host == "localhost" || host == "127.0.0.1"
    }
}

fun parseUrl(url: String): ParsedUrl? =
    try {
        var uri = java.net.URI(url)
        if (uri.scheme == null) {
            uri = java.net.URI("https://$url")
        }
        val scheme = uri.scheme
        val host = uri.host
        val port = if (uri.port > 0) uri.port else null
        if (scheme == null || host == null) {
            return null
        }
        return ParsedUrl(scheme, host, port)
    } catch (e: Exception) {
        logger.error(e) { "Failed to parse URL: $url" }
        null
    }
