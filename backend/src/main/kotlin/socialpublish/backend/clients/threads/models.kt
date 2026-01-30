package socialpublish.backend.clients.threads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class ThreadsConfig(
    val accessToken: String,
    val userId: String,
    val apiBase: String = "https://graph.threads.net",
)

@Serializable data class ThreadsMediaContainerResponse(val id: String)

@Serializable data class ThreadsPublishResponse(val id: String)

@Serializable data class ThreadsErrorResponse(val error: ThreadsError)

@Serializable
data class ThreadsError(
    val message: String,
    val type: String,
    val code: Int,
    @SerialName("fbtrace_id") val fbtraceId: String? = null,
)
