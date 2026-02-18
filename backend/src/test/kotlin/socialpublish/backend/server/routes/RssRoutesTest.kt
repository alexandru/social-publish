package socialpublish.backend.server.routes

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.modules.RssModule
import socialpublish.backend.testutils.createTestDatabase

@Serializable data class RssPostResponse(val uri: String, val module: String)

private val testUserUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
private val testUserUuidPath = testUserUuid.toString()

class RssRoutesTest {
    @Test
    fun `createPostRoute accepts JSON request`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val postsDb =
            socialpublish.backend.db.PostsDatabase(socialpublish.backend.db.DocumentsDatabase(jdbi))
        val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)
        val rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
        val rssRoutes = RssRoutes(rssModule)

        application {
            install(ContentNegotiation) { json() }
            routing { post("/api/rss/post") { rssRoutes.createPostRoute(testUserUuid, call) } }
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

        val request = NewPostRequest(content = "Test post via JSON", targets = listOf("rss"))

        val response =
            client.post("/api/rss/post") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("http://localhost:3000/rss/"))
        assertTrue(body.contains("\"module\":\"rss\""))

        client.close()
    }

    @Test
    fun `createPostRoute validates empty content`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val postsDb =
            socialpublish.backend.db.PostsDatabase(socialpublish.backend.db.DocumentsDatabase(jdbi))
        val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)
        val rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
        val rssRoutes = RssRoutes(rssModule)

        application {
            install(ContentNegotiation) { json() }
            routing { post("/api/rss/post") { rssRoutes.createPostRoute(testUserUuid, call) } }
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

        val request = NewPostRequest(content = "", targets = listOf("rss"))

        val response =
            client.post("/api/rss/post") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("between 1 and 1000 characters"))

        client.close()
    }

    @Test
    fun `generateRssRoute returns RSS feed`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val postsDb =
            socialpublish.backend.db.PostsDatabase(socialpublish.backend.db.DocumentsDatabase(jdbi))
        val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)
        val rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
        val rssRoutes = RssRoutes(rssModule)

        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/api/rss/post") { rssRoutes.createPostRoute(testUserUuid, call) }
                get("/rss/{userUuid}") { rssRoutes.generateRssRoute(call) }
            }
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

        // Create a post first
        client.post("/api/rss/post") {
            contentType(ContentType.Application.Json)
            setBody(NewPostRequest(content = "Test RSS feed content"))
        }

        // Get the RSS feed
        val response = client.get("/rss/$testUserUuidPath")

        assertEquals(HttpStatusCode.OK, response.status)
        // Check content type starts with application/rss+xml (may have charset)
        assertTrue(response.contentType().toString().startsWith("application/rss+xml"))
        val body = response.bodyAsText()
        assertTrue(body.contains("<?xml"))
        assertTrue(body.contains("<rss"))
        assertTrue(body.contains("Test RSS feed content"))

        client.close()
    }

    @Test
    fun `generateRssRoute with filterByLinks parameter`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val postsDb =
            socialpublish.backend.db.PostsDatabase(socialpublish.backend.db.DocumentsDatabase(jdbi))
        val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)
        val rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
        val rssRoutes = RssRoutes(rssModule)

        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/api/rss/post") { rssRoutes.createPostRoute(testUserUuid, call) }
                get("/rss/{userUuid}") { rssRoutes.generateRssRoute(call) }
            }
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

        // Create posts
        client.post("/api/rss/post") {
            contentType(ContentType.Application.Json)
            setBody(NewPostRequest(content = "Post with link", link = "https://example.com"))
        }
        client.post("/api/rss/post") {
            contentType(ContentType.Application.Json)
            setBody(NewPostRequest(content = "Post without link"))
        }

        // Get RSS with filterByLinks=include
        val response = client.get("/rss/$testUserUuidPath") { parameter("filterByLinks", "include") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Post with link"))
        assertFalse(body.contains("Post without link"))

        client.close()
    }

    @Test
    fun `generateRssRoute with target parameter`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val postsDb =
            socialpublish.backend.db.PostsDatabase(socialpublish.backend.db.DocumentsDatabase(jdbi))
        val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)
        val rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
        val rssRoutes = RssRoutes(rssModule)

        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/api/rss/post") { rssRoutes.createPostRoute(testUserUuid, call) }
                get("/rss/{userUuid}/target/{target}") { rssRoutes.generateRssRoute(call) }
            }
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

        // Create posts with different targets
        client.post("/api/rss/post") {
            contentType(ContentType.Application.Json)
            setBody(NewPostRequest(content = "Twitter post", targets = listOf("twitter")))
        }
        client.post("/api/rss/post") {
            contentType(ContentType.Application.Json)
            setBody(NewPostRequest(content = "Mastodon post", targets = listOf("mastodon")))
        }

        // Get RSS for twitter target
        val response = client.get("/rss/$testUserUuidPath/target/twitter")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Twitter post"))
        assertFalse(body.contains("Mastodon post"))

        client.close()
    }

    @Test
    fun `getRssItem returns post when found`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val postsDb =
            socialpublish.backend.db.PostsDatabase(socialpublish.backend.db.DocumentsDatabase(jdbi))
        val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)
        val rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
        val rssRoutes = RssRoutes(rssModule)

        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/api/rss/post") { rssRoutes.createPostRoute(testUserUuid, call) }
                get("/rss/{userUuid}/{uuid}") { rssRoutes.getRssItem(call) }
            }
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

        // Create a post
        val createResponse =
            client.post("/api/rss/post") {
                contentType(ContentType.Application.Json)
                setBody(NewPostRequest(content = "Test post for retrieval"))
            }
        val createBody = createResponse.bodyAsText()
        // Extract UUID from the response URI
        val uuidMatch = Regex("""/rss/[a-f0-9-]+/([a-f0-9-]+)""").find(createBody)
        assertNotNull(uuidMatch)
        val uuid = uuidMatch!!.groupValues[1]

        // Get the post by UUID
        val response = client.get("/rss/$testUserUuidPath/$uuid")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Test post for retrieval"))
        assertTrue(body.contains(uuid))

        client.close()
    }

    @Test
    fun `getRssItem returns not found for invalid UUID`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val postsDb =
            socialpublish.backend.db.PostsDatabase(socialpublish.backend.db.DocumentsDatabase(jdbi))
        val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)
        val rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
        val rssRoutes = RssRoutes(rssModule)

        application {
            install(ContentNegotiation) { json() }
            routing { get("/rss/{userUuid}/{uuid}") { rssRoutes.getRssItem(call) } }
        }

        val response = client.get("/rss/$testUserUuidPath/nonexistent-uuid")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Post not found"))
    }

    @Test
    fun `getRssItem returns bad request when UUID is missing`(@TempDir tempDir: Path) =
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val postsDb =
                socialpublish.backend.db.PostsDatabase(
                    socialpublish.backend.db.DocumentsDatabase(jdbi)
                )
            val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)
            val rssModule = RssModule("http://localhost:3000", postsDb, filesDb)
            val rssRoutes = RssRoutes(rssModule)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    // This simulates a route without the uuid parameter
                    get("/rss/{userUuid}/") { rssRoutes.getRssItem(call) }
                }
            }

            val response = client.get("/rss/$testUserUuidPath/")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Missing UUID"))
        }
}
