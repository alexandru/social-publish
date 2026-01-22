package socialpublish.backend.integrations.linkedin

data class LinkedInConfig(
    val clientId: String,
    val clientSecret: String,
    // OAuth endpoints
    val authorizationUrl: String = "https://www.linkedin.com/oauth/v2/authorization",
    val accessTokenUrl: String = "https://www.linkedin.com/oauth/v2/accessToken",
    // API endpoints
    val apiBase: String = "https://api.linkedin.com/v2",
)
