package socialpublish.backend.clients

import arrow.core.Either
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.mastodon.MastodonApiModule
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.common.NewMastodonPostResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.server.routes.FilesRoutes
import socialpublish.backend.testutils.ImageDimensions
import socialpublish.backend.testutils.TEST_USER_UUID
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase
import socialpublish.backend.testutils.imageDimensions
import socialpublish.backend.testutils.receiveMultipart
import socialpublish.backend.testutils.uploadTestImage

class MastodonApiTest {
    @Test
    fun `uploads images with alt text and creates status`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val filesRoutes = FilesRoutes(filesModule)
            val uploadedImages = mutableListOf<ImageDimensions>()
            val descriptions = mutableListOf<String?>()
            var statusMediaIds: List<String>? = null
            var mediaCounter = 0

            application {
                routing {
                    post("/api/files/upload") { filesRoutes.uploadFileRoute(call) }
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
            val result = mastodonModule.createPost(TEST_USER_UUID, req)

            assertTrue(result.isRight())
            val response = (result as Either.Right).value as NewMastodonPostResponse
            assertNotNull(response.uri)

            assertEquals(listOf("rose", "tulip"), descriptions)
            assertEquals(listOf("media-1", "media-2"), statusMediaIds)
            assertEquals(2, uploadedImages.size)

            // Images are optimized on upload to max 1600x1600
            assertTrue(uploadedImages[0].width <= 1600)
            assertTrue(uploadedImages[0].height <= 1600)
            assertTrue(uploadedImages[1].width <= 1600)
            assertTrue(uploadedImages[1].height <= 1600)

            mastodonClient.close()
        }
    }
}
