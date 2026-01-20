package com.alexn.socialpublish.utils

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set

const val JWT_TOKEN_KEY = "jwt_token"
const val HAS_AUTH_KEY = "has_auth"

object Storage {
    fun getJwtToken(): String? {
        return localStorage[JWT_TOKEN_KEY]
    }

    fun setJwtToken(token: String) {
        localStorage[JWT_TOKEN_KEY] = token
    }

    fun clearJwtToken() {
        localStorage.removeItem(JWT_TOKEN_KEY)
    }

    fun hasJwtToken(): Boolean {
        return getJwtToken() != null
    }

    fun setAuthStatus(hasAuth: Boolean) {
        localStorage[HAS_AUTH_KEY] = hasAuth.toString()
    }

    fun getAuthStatus(): Boolean {
        return localStorage[HAS_AUTH_KEY] == "true"
    }
}
