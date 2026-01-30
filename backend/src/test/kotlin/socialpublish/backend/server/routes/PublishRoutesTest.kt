package socialpublish.backend.server.routes

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.modules.PublishModule
import socialpublish.backend.modules.RssModule
import socialpublish.backend.testutils.createTestDatabase

class PublishRoutesTest {
    @Test
    fun `broadcastPostRoute accepts JSON request`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val postsDb =
            socialpublish.backend.db.PostsDatabase(socialpublish.backend.db.DocumentsDatabase(jdbi))
        val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)
        val rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
        val publishModule = PublishModule(null, null, null, null, null, rssModule)
        val publishRoutes = PublishRoutes(publishModule)

        application {
            install(ContentNegotiation) { json() }
            routing { post("/api/multiple/post") { publishRoutes.broadcastPostRoute(call) } }
        }

        val client = createClient {
            install(ClientContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
        }

        val request = NewPostRequest(content = "Test broadcast via JSON", targets = listOf("rss"))

        val response =
            client.post("/api/multiple/post") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("http://localhost:3000/rss/"))
        assertTrue(body.contains("\"rss\""))

        client.close()
    }

    @Test
    fun `broadcastPostRoute returns error for unconfigured platform`(@TempDir tempDir: Path) =
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val postsDb =
                socialpublish.backend.db.PostsDatabase(
                    socialpublish.backend.db.DocumentsDatabase(jdbi)
                )
            val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)
            val rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
            val publishModule = PublishModule(null, null, null, null, null, rssModule)
            val publishRoutes = PublishRoutes(publishModule)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/multiple/post") { publishRoutes.broadcastPostRoute(call) } }
            }

            val client = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val request = NewPostRequest(content = "Test post", targets = listOf("bluesky"))

            val response =
                client.post("/api/multiple/post") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("not configured") || body.contains("Bluesky"))

            client.close()
        }

    @Test
    fun `broadcastPostRoute returns composite error with details`(@TempDir tempDir: Path) =
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val postsDb =
                socialpublish.backend.db.PostsDatabase(
                    socialpublish.backend.db.DocumentsDatabase(jdbi)
                )
            val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)
            val rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
            val publishModule = PublishModule(null, null, null, null, null, rssModule)
            val publishRoutes = PublishRoutes(publishModule)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/multiple/post") { publishRoutes.broadcastPostRoute(call) } }
            }

            val client = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val request =
                NewPostRequest(
                    content = "Test composite error",
                    targets = listOf("rss", "mastodon"),
                )

            val response =
                client.post("/api/multiple/post") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val body = response.bodyAsText()
            // Should contain composite error with responses array
            assertTrue(body.contains("responses"))
            assertTrue(body.contains("Failed to create post"))

            client.close()
        }
}
