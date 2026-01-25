package socialpublish.backend.clients.llm

data class LlmConfig(
    val provider: String, // "openai" or "mistral"
    val apiKey: String,
    val model: String? = null, // Optional model override
)
