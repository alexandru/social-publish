package socialpublish.backend.clients.llm

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/**
 * Identifies OpenCode Go endpoints, which need provider-specific request
 * metadata. See https://opencode.ai/docs/go/#where-can-i-use-it.
 */
internal fun isOpenCodeGoEndpoint(url: Url): Boolean =
    url.protocol == URLProtocol.HTTPS &&
        url.host.equals("opencode.ai", ignoreCase = true) &&
        url.segments.size >= 3 &&
        url.segments[0] == "zen" &&
        url.segments[1] == "go"
