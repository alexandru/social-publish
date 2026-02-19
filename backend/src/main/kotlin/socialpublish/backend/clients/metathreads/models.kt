package socialpublish.backend.clients.metathreads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MetaThreadsConfig(
    val accessToken: String,
    val userId: String,
    val apiBase: String = "https://graph.threads.net",
)

@Serializable data class MetaThreadsMediaContainerResponse(val id: String)

@Serializable data class MetaThreadsPublishResponse(val id: String)

@Serializable data class MetaThreadsErrorResponse(val error: MetaThreadsError)

@Serializable
data class MetaThreadsError(
    val message: String,
    val type: String,
    val code: Int,
    @SerialName("fbtrace_id") val fbtraceId: String? = null,
)

@Serializable
data class MetaThreadsRefreshTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
)
