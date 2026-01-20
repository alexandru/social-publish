package com.alexn.socialpublish.utils

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set

const val JWT_TOKEN_KEY = "jwt_token"

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
}
