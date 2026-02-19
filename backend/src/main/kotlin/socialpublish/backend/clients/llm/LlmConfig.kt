package socialpublish.backend.clients.llm

import kotlinx.serialization.Serializable

@Serializable
data class LlmConfig(
    val apiUrl: String, // API endpoint URL (e.g., "https://api.openai.com/v1/chat/completions")
    val apiKey: String,
    val model: String, // Model name (e.g., "gpt-4o-mini" or "pixtral-12b-2409")
)
