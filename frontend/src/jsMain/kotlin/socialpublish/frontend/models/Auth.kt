package socialpublish.frontend.models

import kotlinx.serialization.Serializable

@Serializable data class LoginRequest(val username: String, val password: String)

/**
 * Which services are configured for the authenticated user.
 *
 * Mastodon, Bluesky, Twitter, and LinkedIn are social posting targets. For Twitter and LinkedIn,
 * `true` means credentials are stored AND the OAuth flow is complete (ready to post). LLM is a
 * utility integration used for alt-text generation, not a posting target, but is included here so
 * the UI can conditionally show the AI alt-text button.
 */
@Serializable
data class ConfiguredServices(
    val mastodon: Boolean = false,
    val bluesky: Boolean = false,
    val twitter: Boolean = false,
    val linkedin: Boolean = false,
    val metaThreads: Boolean = false,
    val llm: Boolean = false,
)

@Serializable
data class LoginResponse(
    val token: String,
    val configuredServices: ConfiguredServices = ConfiguredServices(),
)
