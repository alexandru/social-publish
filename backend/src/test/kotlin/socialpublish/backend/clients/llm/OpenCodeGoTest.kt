package socialpublish.backend.clients.llm

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenCodeGoTest {
    @Test
    fun `accepts OpenCode Go endpoints`() {
        listOf(
                "https://opencode.ai/zen/go/v1/chat/completions",
                "https://opencode.ai/zen/go/v1/chat/completions?model=kimi",
                "https://OpenCode.ai/zen/go/v1/messages",
                "https://opencode.ai/zen/go/x",
            )
            .forEach { url -> assertTrue(isOpenCodeGoEndpoint(Url(url)), url) }
    }

    @Test
    fun `rejects non-OpenCode Go endpoints`() {
        listOf(
                "http://opencode.ai/zen/go/v1/chat/completions",
                "https://opencode.ai/zen/v1/chat/completions",
                "https://opencode.ai/v1/chat/completions",
                "https://opencode.ai/zen",
                "https://opencode.ai.example.com/zen/go/v1/chat/completions",
                "https://api.opencode.ai/zen/go/v1/chat/completions",
            )
            .forEach { url -> assertFalse(isOpenCodeGoEndpoint(Url(url)), url) }
    }
}
