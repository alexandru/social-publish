package socialpublish.backend.integrations.linkedin

import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.models.NewLinkedInPostResponse
import socialpublish.backend.models.NewPostRequest
import socialpublish.backend.models.ValidationError
import socialpublish.backend.modules.FilesModule
import socialpublish.backend.testutils.uploadTestImageFile

class LinkedInApiTest {
    @Test
    fun `creates text-only post`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db")
        val filesDir = tempDir.resolve("files").also { it.createDirectories() }
        val jdbi = Jdbi.create("jdbc:sqlite:$dbPath")
        val filesDb = FilesDatabase(jdbi)
        filesDb.init()
        val filesModule = FilesModule(filesDb, filesDir.toFile(), "http://localhost")

        var capturedRequest: String? = null

        val mockEngine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("/ugcPosts") &&
                    request.method == HttpMethod.Post -> {
                    capturedRequest = request.body.toString()
                    respond(
                        content = """{"id":"urn:li:share:123456"}""",
                        status = HttpStatusCode.Created,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json.toString(),
                            ),
                    )
                }
                else -> error("Unhandled ${request.url}")
            }
        }

        val config = LinkedInConfig(accessToken = "test-token", personUrn = "urn:li:person:test")
        val module = LinkedInApiModule(config, filesModule, mockEngine)

        val request = NewPostRequest(content = "Hello LinkedIn!", targets = listOf("linkedin"))

        val result = module.createPost(request)

        assertIs<Either.Right<NewLinkedInPostResponse>>(result)
        assertEquals("linkedin", result.value.module)
        assertTrue(result.value.postId.isNotEmpty())
    }

    @Test
    fun `validates empty content`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db")
        val filesDir = tempDir.resolve("files").also { it.createDirectories() }
        val jdbi = Jdbi.create("jdbc:sqlite:$dbPath")
        val filesDb = FilesDatabase(jdbi)
        filesDb.init()
        val filesModule = FilesModule(filesDb, filesDir.toFile(), "http://localhost")

        val mockEngine = MockEngine { error("Should not make requests") }

        val config = LinkedInConfig(accessToken = "test-token", personUrn = "urn:li:person:test")
        val module = LinkedInApiModule(config, filesModule, mockEngine)

        val request = NewPostRequest(content = "", targets = listOf("linkedin"))

        val result = module.createPost(request)

        assertIs<Either.Left<ValidationError>>(result)
        assertEquals(400, result.value.status)
    }

    @Test
    fun `creates post with images`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("test.db")
        val filesDir = tempDir.resolve("files").also { it.createDirectories() }
        val jdbi = Jdbi.create("jdbc:sqlite:$dbPath")
        val filesDb = FilesDatabase(jdbi)
        filesDb.init()
        val filesModule = FilesModule(filesDb, filesDir.toFile(), "http://localhost")

        val imageUuid = uploadTestImageFile(filesModule, altText = "Test image")

        var uploadCalled = false
        var postCalled = false

        val mockEngine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("/assets") &&
                    request.method == HttpMethod.Post -> {
                    uploadCalled = true
                    respond(
                        content = """{"value":{"asset":"urn:li:digitalmediaAsset:test123"}}""",
                        status = HttpStatusCode.Created,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json.toString(),
                            ),
                    )
                }
                request.url.encodedPath.contains("/images") &&
                    request.method == HttpMethod.Post -> {
                    respond(
                        content = """{"value":{"uploadUrl":"http://upload.example.com/test"}}""",
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json.toString(),
                            ),
                    )
                }
                request.url.encodedPath.contains("upload.example.com") -> {
                    respond(content = "", status = HttpStatusCode.Created)
                }
                request.url.encodedPath.contains("/ugcPosts") &&
                    request.method == HttpMethod.Post -> {
                    postCalled = true
                    respond(
                        content = """{"id":"urn:li:share:123456"}""",
                        status = HttpStatusCode.Created,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json.toString(),
                            ),
                    )
                }
                else -> error("Unhandled ${request.url}")
            }
        }

        val config = LinkedInConfig(accessToken = "test-token", personUrn = "urn:li:person:test")
        val module = LinkedInApiModule(config, filesModule, mockEngine)

        val request =
            NewPostRequest(
                content = "Post with image",
                targets = listOf("linkedin"),
                images = listOf(imageUuid),
            )

        val result = module.createPost(request)

        assertIs<Either.Right<NewLinkedInPostResponse>>(result)
        assertTrue(uploadCalled, "Image upload should be called")
        assertTrue(postCalled, "Post creation should be called")
    }
}
