package socialpublish.backend.integrations

import arrow.core.Either
import socialpublish.backend.integrations.mastodon.MastodonApiModule
import socialpublish.backend.integrations.mastodon.MastodonConfig
import socialpublish.backend.models.NewMastodonPostResponse
import socialpublish.backend.models.NewPostRequest
import socialpublish.backend.testutils.ImageDimensions
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase
import socialpublish.backend.testutils.imageDimensions
import socialpublish.backend.testutils.loadTestResourceBytes
import socialpublish.backend.testutils.receiveMultipart
import socialpublish.backend.testutils.uploadTestImage
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir

class MastodonApiTest {
    @Test
    fun `uploads images with alt text and creates status`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val uploadedImages = mutableListOf<ImageDimensions>()
            val descriptions = mutableListOf<String?>()
            var statusMediaIds: List<String>? = null
            var mediaCounter = 0

            application {
                routing {
                    post("/api/files/upload") {
                        val result = filesModule.uploadFile(call)
                        when (result) {
                            is Either.Right ->
                                call.respondText(
                                    Json.encodeToString(result.value),
                                    io.ktor.http.ContentType.Application.Json,
                                )
                            is Either.Left ->
                                call.respondText(
                                    "{\"error\":\"${result.value.errorMessage}\"}",
                                    io.ktor.http.ContentType.Application.Json,
                                    io.ktor.http.HttpStatusCode.fromValue(result.value.status),
                                )
                        }
                    }
                    post("/api/v2/media") {
                        val multipart = receiveMultipart(call)
                        val file = multipart.files.single()
                        uploadedImages.add(imageDimensions(file.bytes))
                        descriptions.add(multipart.fields["description"]?.firstOrNull())
                        mediaCounter += 1
                        call.respondText(
                            "{" +
                                "\"id\":\"media-$mediaCounter\",\"url\":\"http://media.local/$mediaCounter\"}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/api/v1/statuses") {
                        val params = call.receiveParameters()
                        statusMediaIds = params.getAll("media_ids[]")
                        call.respondText(
                            "{" +
                                "\"id\":\"status-1\",\"uri\":\"http://mastodon.local/status/1\",\"url\":\"http://mastodon.local/s/1\"}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                }
            }

            val mastodonClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val upload1 = uploadTestImage(mastodonClient, "flower1.jpeg", "rose")
            val upload2 = uploadTestImage(mastodonClient, "flower2.jpeg", "tulip")

            val mastodonModule =
                MastodonApiModule(
                    MastodonConfig(host = "http://localhost", accessToken = "token"),
                    filesModule,
                    mastodonClient,
                )

            val req = NewPostRequest(content = "Hello", images = listOf(upload1.uuid, upload2.uuid))
            val result = mastodonModule.createPost(req)

            assertTrue(result.isRight())
            val response = (result as Either.Right).value as NewMastodonPostResponse
            assertNotNull(response.uri)

            assertEquals(listOf("rose", "tulip"), descriptions)
            assertEquals(listOf("media-1", "media-2"), statusMediaIds)
            assertEquals(2, uploadedImages.size)

            val original1 = imageDimensions(loadTestResourceBytes("flower1.jpeg"))
            val original2 = imageDimensions(loadTestResourceBytes("flower2.jpeg"))
            assertTrue(uploadedImages[0].width <= 1920)
            assertTrue(uploadedImages[0].height <= 1080)
            assertTrue(uploadedImages[1].width <= 1920)
            assertTrue(uploadedImages[1].height <= 1080)
            assertTrue(
                uploadedImages[0].width < original1.width ||
                    uploadedImages[0].height < original1.height
            )
            assertTrue(
                uploadedImages[1].width < original2.width ||
                    uploadedImages[1].height < original2.height
            )

            mastodonClient.close()
        }
    }
}
