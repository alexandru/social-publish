package com.alexn.socialpublish.models

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    val hasAuth: AuthStatus,
)

@Serializable
data class AuthStatus(
    val twitter: Boolean = false,
)

