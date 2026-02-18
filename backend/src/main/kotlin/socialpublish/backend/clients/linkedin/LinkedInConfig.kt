package socialpublish.backend.clients.linkedin

import kotlinx.serialization.Serializable

/**
 * Configuration for LinkedIn OAuth2 and API integration.
 *
 * Contains the client credentials and endpoint URLs required for authenticating with LinkedIn and
 * making API calls.
 *
 * ## Obtaining Credentials
 * 1. Go to [LinkedIn Developer Portal](https://www.linkedin.com/developers/apps)
 * 2. Create a new app or select an existing one
 * 3. Navigate to the "Auth" tab
 * 4. Copy the "Client ID" and "Client Secret"
 * 5. Add your redirect URL: `{your-domain}/api/linkedin/callback`
 *
 * ## Required Products
 *
 * In the "Products" tab, request access to:
 * - **Sign In with LinkedIn using OpenID Connect** - For user authentication
 * - **Share on LinkedIn** - For posting content
 *
 * @property clientId OAuth2 client ID from LinkedIn Developer Portal
 * @property clientSecret OAuth2 client secret from LinkedIn Developer Portal
 * @property authorizationUrl LinkedIn's OAuth2 authorization endpoint
 * @property accessTokenUrl LinkedIn's OAuth2 token endpoint
 * @property apiBase Base URL for LinkedIn API v2 endpoints
 * @see
 *   [LinkedIn OAuth Documentation](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authentication)
 */
@Serializable
data class LinkedInConfig(
    val clientId: String,
    val clientSecret: String,
    // OAuth endpoints
    val authorizationUrl: String = "https://www.linkedin.com/oauth/v2/authorization",
    val accessTokenUrl: String = "https://www.linkedin.com/oauth/v2/accessToken",
    // API endpoints
    val apiBase: String = "https://api.linkedin.com/v2",
)
