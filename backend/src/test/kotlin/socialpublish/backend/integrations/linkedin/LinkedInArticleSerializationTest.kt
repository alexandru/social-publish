package socialpublish.backend.integrations.linkedin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

class LinkedInArticleSerializationTest {
    private val json = Json {
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `article media serialization does not include asset media field`() {
        val media =
            UgcMedia(
                status = "READY",
                originalUrl = "https://example.com/article",
                title = UgcText("Example Title"),
                description = UgcText("Summary"),
                // Intentionally would-be thumbnail URN is not set for ARTICLE
            )

        val shareContent =
            UgcShareContent(
                shareCommentary = UgcText("text"),
                shareMediaCategory = UgcMediaCategory.ARTICLE,
                media = listOf(media),
            )
        val specific = UgcSpecificContent(shareContent = shareContent)
        val request =
            UgcPostRequest(
                author = "urn:li:person:123",
                specificContent = specific,
                visibility = UgcVisibility(UgcVisibilityType.PUBLIC),
            )

        val out = json.encodeToString(UgcPostRequest.serializer(), request)

        // The serialized JSON must not contain a '"media": "urn:li:digitalmedia' entry inside the
        // media item
        assertFalse(
            out.contains("\"media\":\"urn:li:digitalmedia"),
            "Serialized ARTICLE media must not include an asset URN",
        )
    }
}
