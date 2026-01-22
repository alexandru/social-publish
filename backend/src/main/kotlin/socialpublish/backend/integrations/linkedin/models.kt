package socialpublish.backend.integrations.linkedin

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================================================
// OAuth Models
// ============================================================================

/** Token refresh buffer: refresh 5 minutes before expiry */
const val TOKEN_REFRESH_BUFFER_SECONDS = 300L

/**
 * OAuth2 access token with refresh token and expiration tracking.
 *
 * LinkedIn access tokens expire after 60 days, and refresh tokens expire after 1 year. The token is
 * considered expired 5 minutes before actual expiry to allow for refresh.
 *
 * **API Reference:**
 * - [Access Token
 *   Expiration](https://learn.microsoft.com/en-us/linkedin/shared/authentication/programmatic-refresh-tokens)
 */
@Serializable
data class LinkedInOAuthToken(
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String? = null,
    val refreshTokenExpiresIn: Long? = null,
    val scope: String? = null,
    val obtainedAt: Long = Instant.now().epochSecond,
) {
    fun isExpired(): Boolean {
        val now = Instant.now().epochSecond
        return (now - obtainedAt) >= (expiresIn - TOKEN_REFRESH_BUFFER_SECONDS)
    }
}

/**
 * Response from LinkedIn's OAuth2 token endpoint.
 *
 * **API Reference:**
 * - [Token
 *   Exchange](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow#step-3-exchange-authorization-code-for-an-access-token)
 *
 * Sample response:
 * ```json
 * {
 *   "access_token": "AQUvlL_DYEzvT2wz1QJiEPeLioeA",
 *   "expires_in": 5184000,
 *   "refresh_token": "AQWAft_WjYZKwuWXLC5hQlghgTam...",
 *   "refresh_token_expires_in": 31536000,
 *   "scope": "r_basicprofile w_member_social"
 * }
 * ```
 */
@Serializable
data class LinkedInTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_token_expires_in") val refreshTokenExpiresIn: Long? = null,
    val scope: String? = null,
)

/**
 * OAuth error response from LinkedIn.
 *
 * Returned when authorization fails or token exchange fails.
 *
 * **Error codes for /oauth/v2/authorization:**
 * - `user_cancelled_login` - The member declined to log in
 * - `user_cancelled_authorize` - The member refused permissions
 *
 * **Error codes for /oauth/v2/accessToken:**
 * - `invalid_request` - Missing required parameters
 * - `invalid_redirect_uri` - URI mismatch or code expired
 */
@Serializable
data class LinkedInOAuthError(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
)

// ============================================================================
// User Profile Models
// ============================================================================

/**
 * User profile from LinkedIn's OIDC UserInfo endpoint.
 *
 * The `sub` (subject) field contains the LinkedIn member identifier, which may be either a plain ID
 * (e.g., "abc123") or full URN format (e.g., "urn:li:person:abc123").
 *
 * **API Reference:**
 * - [OIDC
 *   UserInfo](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2#retrieving-member-profiles)
 */
@Serializable
data class LinkedInUserProfile(
    /** Subject identifier from OIDC userinfo endpoint */
    val sub: String,
    val name: String? = null,
    val email: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean? = null,
    val picture: String? = null,
)

// ============================================================================
// Status Response Model
// ============================================================================

/** Response for LinkedIn authorization status check */
@Serializable
data class LinkedInStatusResponse(val hasAuthorization: Boolean, val createdAt: Long? = null)

// ============================================================================
// Image/Media Upload Models (Assets API)
// ============================================================================

/**
 * Request to register an image/video upload with LinkedIn.
 *
 * **API Endpoint:** `POST https://api.linkedin.com/v2/assets?action=registerUpload`
 *
 * **Sample request:**
 *
 * ```json
 * {
 *   "registerUploadRequest": {
 *     "recipes": ["urn:li:digitalmediaRecipe:feedshare-image"],
 *     "owner": "urn:li:person:8675309",
 *     "serviceRelationships": [{
 *       "relationshipType": "OWNER",
 *       "identifier": "urn:li:userGeneratedContent"
 *     }]
 *   }
 * }
 * ```
 */
@Serializable
data class LinkedInRegisterUploadRequest(val registerUploadRequest: RegisterUploadRequestData)

@Serializable
data class RegisterUploadRequestData(
    val owner: String,
    val recipes: List<String>,
    val serviceRelationships: List<ServiceRelationship>,
)

@Serializable data class ServiceRelationship(val identifier: String, val relationshipType: String)

/**
 * Response from LinkedIn's register upload endpoint.
 *
 * **Sample response:**
 *
 * ```json
 * {
 *   "value": {
 *     "uploadMechanism": {
 *       "com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest": {
 *         "headers": {},
 *         "uploadUrl": "https://api.linkedin.com/mediaUpload/..."
 *       }
 *     },
 *     "mediaArtifact": "urn:li:digitalmediaMediaArtifact:(...)",
 *     "asset": "urn:li:digitalmediaAsset:C5522AQGTYER3k3ByHQ"
 *   }
 * }
 * ```
 */
@Serializable data class LinkedInRegisterUploadResponse(val value: RegisterUploadValue)

@Serializable
data class RegisterUploadValue(
    val asset: String,
    val uploadMechanism: UploadMechanism,
    val mediaArtifact: String? = null,
)

@Serializable
data class UploadMechanism(
    @SerialName("com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest")
    val uploadRequest: MediaUploadHttpRequest
)

@Serializable
data class MediaUploadHttpRequest(val uploadUrl: String, val headers: Map<String, String>? = null)

// ============================================================================
// UGC Post Models (User Generated Content API)
// ============================================================================

/**
 * Request body for creating a post via LinkedIn's UGC API.
 *
 * **API Endpoint:** `POST https://api.linkedin.com/v2/ugcPosts`
 *
 * **Required Header:** `X-Restli-Protocol-Version: 2.0.0`
 *
 * **Sample text-only request:**
 *
 * ```json
 * {
 *   "author": "urn:li:person:8675309",
 *   "lifecycleState": "PUBLISHED",
 *   "specificContent": {
 *     "com.linkedin.ugc.ShareContent": {
 *       "shareCommentary": {"text": "Hello World!"},
 *       "shareMediaCategory": "NONE"
 *     }
 *   },
 *   "visibility": {
 *     "com.linkedin.ugc.MemberNetworkVisibility": "PUBLIC"
 *   }
 * }
 * ```
 */
@Serializable
data class UgcPostRequest(
    val author: String,
    val lifecycleState: String = "PUBLISHED",
    val specificContent: UgcSpecificContent,
    val visibility: UgcVisibility,
)

/**
 * Wrapper for UGC share content.
 *
 * Uses the discriminator key `com.linkedin.ugc.ShareContent` as required by LinkedIn's API.
 */
@Serializable
data class UgcSpecificContent(
    @SerialName("com.linkedin.ugc.ShareContent") val shareContent: UgcShareContent
)

/**
 * Share content details for UGC posts.
 *
 * @property shareCommentary Primary text content for the share
 * @property shareMediaCategory Type of media: `NONE`, `ARTICLE`, `IMAGE`, or `VIDEO`
 * @property media Media assets if shareMediaCategory is not `NONE`
 */
@Serializable
data class UgcShareContent(
    val shareCommentary: UgcText,
    val shareMediaCategory: String,
    val media: List<UgcMedia>? = null,
)

/** Text wrapper used in UGC posts */
@Serializable data class UgcText(val text: String)

/**
 * Media item for UGC posts.
 *
 * **For ARTICLE shares:**
 *
 * ```json
 * {
 *   "status": "READY",
 *   "originalUrl": "https://blog.linkedin.com/",
 *   "title": {"text": "LinkedIn Blog"},
 *   "description": {"text": "Description here"}
 * }
 * ```
 *
 * **For IMAGE shares:**
 *
 * ```json
 * {
 *   "status": "READY",
 *   "media": "urn:li:digitalmediaAsset:C5422AQEbc381YmIuvg",
 *   "title": {"text": "Image Title"}
 * }
 * ```
 */
@Serializable
data class UgcMedia(
    val status: String = "READY",
    val description: UgcText? = null,
    val media: String? = null,
    val originalUrl: String? = null,
    val title: UgcText? = null,
)

/**
 * Visibility wrapper for UGC posts.
 *
 * Uses the discriminator key `com.linkedin.ugc.MemberNetworkVisibility`.
 *
 * Possible values:
 * - `CONNECTIONS` - Viewable by 1st-degree connections only
 * - `PUBLIC` - Viewable by anyone on LinkedIn
 */
@Serializable
data class UgcVisibility(
    @SerialName("com.linkedin.ugc.MemberNetworkVisibility") val visibility: String
)

/**
 * Response from creating a UGC post.
 *
 * A successful response returns `201 Created` with the post ID in the `X-RestLi-Id` header. The
 * response body may also contain the ID.
 */
@Serializable data class UgcPostResponse(val id: String? = null)

// ============================================================================
// LinkedIn API Error Models
// ============================================================================

/**
 * LinkedIn API error response.
 *
 * LinkedIn returns errors in various formats depending on the API endpoint.
 */
@Serializable
data class LinkedInApiError(
    val message: String? = null,
    val status: Int? = null,
    val serviceErrorCode: Int? = null,
)

// ============================================================================
// Helper Enums/Constants
// ============================================================================

/** LinkedIn media categories for UGC posts */
object UgcMediaCategory {
    const val NONE = "NONE"
    const val ARTICLE = "ARTICLE"
    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
}

/** LinkedIn visibility options for UGC posts */
object UgcVisibilityType {
    const val PUBLIC = "PUBLIC"
    const val CONNECTIONS = "CONNECTIONS"
}

/** LinkedIn lifecycle states for posts */
object UgcLifecycleState {
    const val PUBLISHED = "PUBLISHED"
    const val DRAFT = "DRAFT"
}

/** Digital media recipes for asset registration */
object LinkedInMediaRecipe {
    const val FEEDSHARE_IMAGE = "urn:li:digitalmediaRecipe:feedshare-image"
    const val FEEDSHARE_VIDEO = "urn:li:digitalmediaRecipe:feedshare-video"
}
