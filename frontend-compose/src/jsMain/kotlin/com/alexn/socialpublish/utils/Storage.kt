package com.alexn.socialpublish.utils

import com.alexn.socialpublish.models.AuthStatus
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import org.w3c.dom.get
import org.w3c.dom.set

object Storage {
    private const val ACCESS_TOKEN_COOKIE = "access_token"
    private const val AUTH_STATUS_KEY = "hasAuth"
    
    // Cookie utilities
    fun cookies(): Map<String, String> {
        return document.cookie.split("; ").mapNotNull { cookie ->
            val parts = cookie.split("=")
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }
    
    fun setCookie(name: String, value: String, expirationMillis: Long? = null) {
        val expires = if (expirationMillis != null) {
            val expiryDate = kotlin.js.Date()
            expiryDate.asDynamic().setTime(expiryDate.getTime() + expirationMillis)
            ";expires=${expiryDate.toUTCString()}"
        } else {
            ""
        }
        document.cookie = "$name=$value$expires;path=/"
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
        // 2 days expiration
        setCookie(ACCESS_TOKEN_COOKIE, token, 1000L * 60 * 60 * 24 * 2)
    }

    fun clearJwtToken() {
        clearCookie(ACCESS_TOKEN_COOKIE)
    }

    fun hasJwtToken(): Boolean {
        return getJwtToken() != null
    }
    
    // Auth status management (using localStorage)
    fun setAuthStatus(hasAuth: AuthStatus?) {
        if (hasAuth == null) {
            localStorage.removeItem(AUTH_STATUS_KEY)
        } else {
            localStorage[AUTH_STATUS_KEY] = Json.encodeToString(hasAuth)
        }
    }
    
    fun getAuthStatus(): AuthStatus {
        val stored = localStorage[AUTH_STATUS_KEY]
        return if (stored != null) {
            try {
                Json.decodeFromString<AuthStatus>(stored)
            } catch (e: Exception) {
                console.error("Error decoding AuthStatus from localStorage:", e)
                AuthStatus()
            }
        } else {
            AuthStatus()
        }
    }
    
    fun updateAuthStatus(f: (AuthStatus) -> AuthStatus) {
        val current = getAuthStatus()
        setAuthStatus(f(current))
    }
}
