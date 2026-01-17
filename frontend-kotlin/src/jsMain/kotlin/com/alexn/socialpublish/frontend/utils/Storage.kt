package com.alexn.socialpublish.frontend.utils

import kotlin.js.Date
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import web.dom.document
import web.storage.localStorage


@Serializable
data class HasAuth(
    val twitter: Boolean = false,
)

fun cookies(): Map<String, String> {
    val rawCookie = document.cookie
    if (rawCookie.isBlank()) {
        return emptyMap()
    }
    return rawCookie.split("; ").associate { entry ->
        val parts = entry.split("=", limit = 2)
        val name = parts.first()
        val value = parts.getOrNull(1) ?: ""
        name to value
    }
}

fun setCookie(name: String, value: String, expirationMillis: Int? = null) {
    val expires = if (expirationMillis != null) {
        val date = Date(Date().getTime() + expirationMillis)
        ";expires=${date.toUTCString()}"
    } else {
        ""
    }
    document.cookie = "$name=$value$expires;path=/"
}

fun clearCookie(name: String) {
    val date = Date(0)
    document.cookie = "$name=;expires=${date.toUTCString()};path=/"
}

fun getJwtToken(): String? = cookies()["access_token"]

fun clearJwtToken() = clearCookie("access_token")

fun setJwtToken(token: String) = setCookie("access_token", token, 1000 * 60 * 60 * 24 * 2)

fun hasJwtToken(): Boolean = getJwtToken() != null

fun setAuthStatus(hasAuth: HasAuth?) {
    if (hasAuth == null) {
        localStorage.removeItem("hasAuth")
    } else {
        val json = Json.encodeToString(hasAuth)
        localStorage.setItem("hasAuth", json)
    }
}

fun getAuthStatus(): HasAuth {
    val raw = localStorage.getItem("hasAuth") ?: return HasAuth(twitter = false)
    return try {
        Json.decodeFromString<HasAuth>(raw)
    } catch (e: Exception) {
        HasAuth(twitter = false)
    }
}

fun updateAuthStatus(transform: (HasAuth) -> HasAuth) {
    val current = getAuthStatus()
    setAuthStatus(transform(current))
}
