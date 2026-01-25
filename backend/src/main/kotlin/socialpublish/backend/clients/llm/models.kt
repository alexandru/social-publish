package socialpublish.backend.clients.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class GenerateAltTextRequest(val imageUuid: String)

@Serializable data class GenerateAltTextResponse(val altText: String)

// OpenAI API models
@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 300,
)

@Serializable data class OpenAiMessage(val role: String, val content: List<OpenAiContent>)

@Serializable
data class OpenAiContent(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: OpenAiImageUrl? = null,
)

@Serializable data class OpenAiImageUrl(val url: String)

@Serializable data class OpenAiChatResponse(val choices: List<OpenAiChoice>)

@Serializable data class OpenAiChoice(val message: OpenAiResponseMessage)

@Serializable data class OpenAiResponseMessage(val content: String)

// Mistral API models (similar structure to OpenAI)
@Serializable
data class MistralChatRequest(
    val model: String,
    val messages: List<MistralMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 300,
)

@Serializable data class MistralMessage(val role: String, val content: List<MistralContent>)

@Serializable
data class MistralContent(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable data class MistralChatResponse(val choices: List<MistralChoice>)

@Serializable data class MistralChoice(val message: MistralResponseMessage)

@Serializable data class MistralResponseMessage(val content: String)
