package socialpublish.backend.linkpreview

import java.net.URI

/**
 * Resolves a potentially relative image URL against a base URL.
 *
 * @param imageUrl The image URL (which may be relative or absolute)
 * @param baseUrl The base URL to resolve against
 * @return The resolved absolute URL, or null if the base URL is malformed
 */
fun resolveImageUrl(imageUrl: String, baseUrl: String): String? {
    // First validate the base URL for all relative URL cases
    val isBaseUrlValid =
        try {
            val baseUri = URI(baseUrl)
            baseUri.scheme != null && baseUri.host != null
        } catch (_: Exception) {
            false
        }

    return when {
        // Already absolute URL (has protocol)
        imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> imageUrl

        // Protocol-relative URL (starts with //)
        imageUrl.startsWith("//") -> {
            if (!isBaseUrlValid) {
                null
            } else {
                val protocol = if (baseUrl.startsWith("https://")) "https:" else "http:"
                "$protocol$imageUrl"
            }
        }

        // Root-relative URL (starts with /)
        imageUrl.startsWith("/") -> {
            if (!isBaseUrlValid) {
                null
            } else {
                try {
                    val baseUri = URI(baseUrl)
                    "${baseUri.scheme}://${baseUri.host}${if (baseUri.port != -1) ":${baseUri.port}" else ""}$imageUrl"
                } catch (_: Exception) {
                    null
                }
            }
        }

        // Path-relative URL - resolve against base URL directory
        else -> {
            if (!isBaseUrlValid) {
                null
            } else {
                try {
                    val baseUri = URI(baseUrl)
                    baseUri.resolve(imageUrl).toString()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
