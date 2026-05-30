package socialpublish.backend.clients.twitter

import arrow.core.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TwitterOAuthDocumentSerializationTest {
    @Test
    fun `parse reads new wrapper format with access token`() {
        val payload =
            """{"accessToken":{"key":"access-key","secret":"access-secret"},"pendingRequest":null}"""

        val parsed = TwitterOAuthDocument.parse(payload)

        val document =
            assertIs<Either.Right<TwitterOAuthDocument>>(parsed).value
        assertEquals(
            TwitterOAuthToken("access-key", "access-secret"),
            document.accessToken,
        )
        assertNull(document.pendingRequest)
    }

    @Test
    fun `parse reads new wrapper format with pending request`() {
        val payload =
            """{"accessToken":null,"pendingRequest":{"token":"request-token","secret":"request-secret"}}"""

        val parsed = TwitterOAuthDocument.parse(payload)

        val document =
            assertIs<Either.Right<TwitterOAuthDocument>>(parsed).value
        assertNull(document.accessToken)
        assertEquals(
            TwitterOAuthRequestToken("request-token", "request-secret"),
            document.pendingRequest,
        )
    }

    @Test
    fun `parse reads old bare token format`() {
        val payload = """{"key":"old-key","secret":"old-secret"}"""

        val parsed = TwitterOAuthDocument.parse(payload)

        val document =
            assertIs<Either.Right<TwitterOAuthDocument>>(parsed).value
        assertEquals(
            TwitterOAuthToken("old-key", "old-secret"),
            document.accessToken,
        )
        assertNull(document.pendingRequest)
    }

    @Test
    fun `parse returns error for invalid payload`() {
        val parsed = TwitterOAuthDocument.parse("not json")

        assertIs<Either.Left<IllegalArgumentException>>(parsed)
    }

    @Test
    fun `serialized document round trips through parser`() {
        val document =
            TwitterOAuthDocument(
                accessToken = TwitterOAuthToken("access-key", "access-secret"),
                pendingRequest =
                    TwitterOAuthRequestToken("request-token", "request-secret"),
            )

        val parsed = TwitterOAuthDocument.parse(document.toJson())

        assertEquals(
            document,
            assertIs<Either.Right<TwitterOAuthDocument>>(parsed).value,
        )
    }
}
