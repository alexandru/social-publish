package socialpublish.backend.modules

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class LinkPreviewFetcherTest {
    private fun clientWithResponse(status: HttpStatusCode, body: String = ""): HttpClient =
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

    @Test
    fun `returns null on redirect`() = io.kotest.common.runBlocking {
        val client = clientWithResponse(HttpStatusCode.Found)
        val fetcher = LinkPreviewFetcher(client)

        val result = fetcher.fetch("https://example.com")

        assertNull(result)
    }

    @Test
    fun `parses og metadata`() = io.kotest.common.runBlocking {
        val html =
            """
            <html>
              <head>
                <meta property="og:title" content="Example title" />
                <meta property="og:description" content="Example desc" />
                <meta property="og:image" content="https://cdn/img.png" />
              </head>
            </html>
            """
                .trimIndent()
        val client = clientWithResponse(HttpStatusCode.OK, html)
        val fetcher = LinkPreviewFetcher(client)

        val result = fetcher.fetch("https://example.com")

        assertEquals(
            LinkPreviewMetadata(
                title = "Example title",
                description = "Example desc",
                thumbnail = "https://cdn/img.png",
            ),
            result,
        )
    }
}
