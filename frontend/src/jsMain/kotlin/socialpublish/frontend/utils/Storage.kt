package socialpublish.frontend.utils

import kotlin.js.Date
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * Per-user identity and configuration, cached locally by the client. Populated
 * from `/api/protected` and consumed by the UI to render state badges and build
 * per-user URLs (e.g. the feed).
 *
 * - [userUuid] identifies the current user; the navbar uses it to build the
 *   `/feed/{userUuid}` link.
 * - [configuredServices] drives UI badges (configured / not configured pills on
 *   the publish form and account page).
 */
@Serializable
data class SessionInfo(
    val userUuid: String = "",
    val configuredServices: ConfiguredServices = ConfiguredServices(),
)

/**
 * Which services are configured for the authenticated user.
 *
 * Mastodon, Bluesky, Twitter, and LinkedIn are social posting targets. For
 * Twitter and LinkedIn, `true` means credentials are stored AND the OAuth flow
 * is complete (ready to post). LLM is a utility integration used for alt-text
 * generation, not a posting target, but is included here so the UI can
 * conditionally show the AI alt-text button.
 */
@Serializable
data class ConfiguredServices(
    val mastodon: Boolean = false,
    val bluesky: Boolean = false,
    val twitter: Boolean = false,
    val linkedin: Boolean = false,
    val llm: Boolean = false,
)

object Storage {
    private const val ACCESS_TOKEN_COOKIE = "access_token"
    private const val CONFIGURED_SERVICES_KEY = "sessionInfo"

    // Cookie utilities
    fun cookies(): Map<String, String> {
        val cookieString = document.cookie
        if (cookieString.isBlank()) {
            return emptyMap()
        }
        return cookieString
            .split("; ")
            .mapNotNull { cookie ->
                val parts = cookie.split("=")
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    fun setCookie(
        name: String,
        value: String,
        expirationMillis: Long? = null,
        secure: Boolean = false,
        sameSite: String? = null,
    ) {
        val expires =
            if (expirationMillis != null) {
                val expiryDate = Date()
                expiryDate
                    .asDynamic()
                    .setTime(expiryDate.getTime() + expirationMillis)
                ";expires=${expiryDate.toUTCString()}"
            } else {
                ""
            }
        val secureFlag = if (secure) ";Secure" else ""
        val sameSiteFlag = if (sameSite != null) ";SameSite=$sameSite" else ""
        document.cookie = "$name=$value$expires;path=/$secureFlag$sameSiteFlag"
    }

    fun clearCookie(name: String) {
        val expiryDate = Date(0)
        document.cookie = "$name=;expires=${expiryDate.toUTCString()};path=/"
    }

    // Session token management (using cookies)
    fun getSessionToken(): String? {
        return cookies()[ACCESS_TOKEN_COOKIE]
    }

    fun setSessionToken(token: String) {
        // Using SameSite=Lax for better compatibility with redirects while
        // maintaining good
        // security
        // Secure flag is optional to allow development over HTTP (will work
        // over HTTPS in
        // production)
        val isHttps = window.location.protocol == "https:"
        val expirationMillis = 1L * 365 * 24 * 60 * 60 * 1000 // 1 year
        setCookie(
            ACCESS_TOKEN_COOKIE,
            token,
            expirationMillis = expirationMillis,
            secure = isHttps,
            sameSite = "Lax",
        )
    }

    fun clearSessionToken() {
        clearCookie(ACCESS_TOKEN_COOKIE)
    }

    fun hasSessionToken(): Boolean {
        return getSessionToken() != null
    }

    // Per-session info (user UUID + configured services) management. Backed by
    // a single localStorage entry so we don't proliferate storage keys. The
    // configured-services API is kept as a pair of convenience accessors
    // because it has many existing call sites; the underlying representation
    // is now a [SessionInfo] that also carries the user UUID.
    fun setConfiguredServices(services: ConfiguredServices?) {
        if (services == null) {
            localStorage.removeItem(CONFIGURED_SERVICES_KEY)
        } else {
            val current = readSessionInfo()
            writeSessionInfo(current.copy(configuredServices = services))
        }
    }

    fun getConfiguredServices(): ConfiguredServices =
        readSessionInfo().configuredServices

    fun getUserUuid(): String? =
        readSessionInfo().userUuid.takeIf { it.isNotEmpty() }

    fun setUserUuid(uuid: String) {
        val current = readSessionInfo()
        writeSessionInfo(current.copy(userUuid = uuid))
    }

    private fun readSessionInfo(): SessionInfo {
        val stored =
            localStorage[CONFIGURED_SERVICES_KEY] ?: return SessionInfo()
        return try {
            Json.decodeFromString<SessionInfo>(stored)
        } catch (e: Throwable) {
            rethrowIfFatal(e)
            console.error(
                "Error decoding SessionInfo from localStorage:",
                e,
                "stored:",
                stored,
            )
            localStorage.removeItem(CONFIGURED_SERVICES_KEY)
            SessionInfo()
        }
    }

    private fun writeSessionInfo(info: SessionInfo) {
        if (info == SessionInfo()) {
            localStorage.removeItem(CONFIGURED_SERVICES_KEY)
        } else {
            localStorage[CONFIGURED_SERVICES_KEY] = Json.encodeToString(info)
        }
    }
}
