package socialpublish.frontend.models

import kotlinx.serialization.Serializable

@Serializable data class LoginRequest(val username: String, val password: String)

@Serializable data class AuthStatus(val twitter: Boolean = false, val linkedin: Boolean = false)

/** Which social network services are configured for the authenticated user. */
@Serializable
data class ConfiguredServices(
    val mastodon: Boolean = false,
    val bluesky: Boolean = false,
    val twitter: Boolean = false,
    val linkedin: Boolean = false,
    val llm: Boolean = false,
)

@Serializable
data class LoginResponse(
    val token: String,
    val hasAuth: AuthStatus,
    val configuredServices: ConfiguredServices = ConfiguredServices(),
)
