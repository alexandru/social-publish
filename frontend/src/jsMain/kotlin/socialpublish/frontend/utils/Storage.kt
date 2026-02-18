package socialpublish.frontend.utils

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import org.w3c.dom.get
import org.w3c.dom.set
import socialpublish.frontend.models.ConfiguredServices

@Serializable private data class JwtPayload(val userUuid: String? = null)

object Storage {
    private const val ACCESS_TOKEN_COOKIE = "access_token"
    private const val CONFIGURED_SERVICES_KEY = "configuredServices"

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
                val expiryDate = kotlin.js.Date()
                expiryDate.asDynamic().setTime(expiryDate.getTime() + expirationMillis)
                ";expires=${expiryDate.toUTCString()}"
            } else {
                ""
            }
        val secureFlag = if (secure) ";Secure" else ""
        val sameSiteFlag = if (sameSite != null) ";SameSite=$sameSite" else ""
        document.cookie = "$name=$value$expires;path=/$secureFlag$sameSiteFlag"
    }

    fun clearCookie(name: String) {
        val expiryDate = kotlin.js.Date(0)
        document.cookie = "$name=;expires=${expiryDate.toUTCString()};path=/"
    }

    // JWT Token management (using cookies)
    fun getJwtToken(): String? {
        return cookies()[ACCESS_TOKEN_COOKIE]
    }

    fun setJwtToken(token: String) {
        // Using SameSite=Lax for better compatibility with redirects while maintaining good
        // security
        // Secure flag is optional to allow development over HTTP (will work over HTTPS in
        // production)
        val isHttps = kotlinx.browser.window.location.protocol == "https:"
        val expirationMillis = 1L * 365 * 24 * 60 * 60 * 1000 // 1 year
        setCookie(
            ACCESS_TOKEN_COOKIE,
            token,
            expirationMillis = expirationMillis,
            secure = isHttps,
            sameSite = "Lax",
        )
    }

    fun clearJwtToken() {
        clearCookie(ACCESS_TOKEN_COOKIE)
    }

    fun hasJwtToken(): Boolean {
        return getJwtToken() != null
    }

    fun getJwtUserUuid(): String? {
        val token = getJwtToken() ?: return null
        val parts = token.split(".")
        if (parts.size < 2) return null

        val payloadBase64 =
            parts[1]
                .replace('-', '+')
                .replace('_', '/')
                .let { segment ->
                    when (segment.length % 4) {
                        2 -> "$segment=="
                        3 -> "$segment="
                        else -> segment
                    }
                }

        return runCatching {
                val payloadJson = window.atob(payloadBase64)
                Json.decodeFromString<JwtPayload>(payloadJson).userUuid
            }
            .getOrNull()
    }

    // Configured services management (using localStorage)
    fun setConfiguredServices(services: ConfiguredServices?) {
        if (services == null) {
            localStorage.removeItem(CONFIGURED_SERVICES_KEY)
        } else {
            localStorage[CONFIGURED_SERVICES_KEY] = Json.encodeToString(services)
        }
    }

    fun getConfiguredServices(): ConfiguredServices {
        val stored = localStorage[CONFIGURED_SERVICES_KEY]
        return if (stored != null) {
            try {
                Json.decodeFromString<ConfiguredServices>(stored)
            } catch (e: Exception) {
                console.error(
                    "Error decoding ConfiguredServices from localStorage:",
                    e,
                    "stored:",
                    stored,
                )
                localStorage.removeItem(CONFIGURED_SERVICES_KEY)
                ConfiguredServices()
            }
        } else {
            ConfiguredServices()
        }
    }
}
