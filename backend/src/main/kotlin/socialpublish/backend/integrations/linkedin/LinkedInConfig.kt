package socialpublish.backend.integrations.linkedin

data class LinkedInConfig(
    val accessToken: String,
    val personUrn: String,
    val apiBase: String = "https://api.linkedin.com/v2",
)
