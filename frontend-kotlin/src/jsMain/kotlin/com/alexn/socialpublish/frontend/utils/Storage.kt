package com.alexn.socialpublish.frontend.utils

import kotlin.js.Date
import kotlin.js.JSON
import web.dom.document
import web.storage.localStorage


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
        ";expires=${'$'}{date.toUTCString()}"
    } else {
        ""
    }
    document.cookie = "${'$'}name=${'$'}value${'$'}expires;path=/"
}

fun clearCookie(name: String) {
    val date = Date(0)
    document.cookie = "${'$'}name=;expires=${'$'}{date.toUTCString()};path=/"
}

fun getJwtToken(): String? = cookies()["access_token"]

fun clearJwtToken() = clearCookie("access_token")

fun setJwtToken(token: String) = setCookie("access_token", token, 1000 * 60 * 60 * 24 * 2)

fun hasJwtToken(): Boolean = getJwtToken() != null

fun <A> storeObjectInLocalStorage(key: String, value: A?) {
    if (value == null) {
        clearObjectFromLocalStorage(key)
    } else {
        localStorage.setItem(key, JSON.stringify(value))
    }
}

inline fun <reified A> getObjectFromLocalStorage(key: String): A? {
    val raw = localStorage.getItem(key) ?: return null
    return JSON.parse<dynamic>(raw).unsafeCast<A>()
}

fun clearObjectFromLocalStorage(key: String) {
    localStorage.removeItem(key)
}

fun setAuthStatus(hasAuth: HasAuth?) = storeObjectInLocalStorage("hasAuth", hasAuth)

fun getAuthStatus(): HasAuth {
    val stored = getObjectFromLocalStorage<HasAuth>("hasAuth")
    return stored ?: HasAuth(twitter = false)
}

fun updateAuthStatus(transform: (HasAuth) -> HasAuth) {
    val current = getAuthStatus()
    setAuthStatus(transform(current))
}
