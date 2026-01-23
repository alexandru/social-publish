package socialpublish.backend.clients

import arrow.core.Either
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.bluesky.BlueskyApiModule
import socialpublish.backend.clients.bluesky.BlueskyConfig
import socialpublish.backend.clients.linkpreview.LinkPreviewParser
import socialpublish.backend.models.NewPostRequest
import socialpublish.backend.testutils.*

class BlueskyApiTest {
    @Test
    fun `creates post without images`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)

            application {
                routing {
                    post("/xrpc/com.atproto.server.createSession") {
                        call.respondText(
                            "{" +
                                "\"accessJwt\":\"atk\",\"refreshJwt\":\"rft\",\"handle\":\"u\",\"did\":\"did:plc:123\"}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/xrpc/com.atproto.repo.createRecord") {
                        call.respondText(
                            "{" +
                                "\"uri\":\"at://did:plc:123/app.bsky.feed.post/1\",\"cid\":\"cid123\"}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                }
            }

            val blueskyClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val linkPreview = LinkPreviewParser(httpClient = blueskyClient)
            val blueskyModule =
                BlueskyApiModule(
                    config =
                        BlueskyConfig(service = "http://localhost", username = "u", password = "p"),
                    filesModule = filesModule,
                    httpClient = blueskyClient,
                    linkPreviewParser = linkPreview,
                )

            val req = NewPostRequest(content = "Hello bluesky")
            val result = blueskyModule.createPost(req)

            assertTrue(result.isRight())
            val _ = (result as Either.Right).value

            blueskyClient.close()
        }
    }

    @Test
    fun `creates post with images and alt text`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val uploadedImages = mutableListOf<ImageDimensions>()
            var createRecordBody: JsonObject? = null

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
                    post("/xrpc/com.atproto.server.createSession") {
                        call.respondText(
                            "{" +
                                "\"accessJwt\":\"atk\",\"refreshJwt\":\"rft\",\"handle\":\"u\",\"did\":\"did:plc:123\"}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/xrpc/com.atproto.repo.uploadBlob") {
                        val bytes = call.receiveStream().readBytes()
                        uploadedImages.add(imageDimensions(bytes))
                        call.respondText(
                            "{" + "\"blob\":{\"ref\":{\"something\":\"ok\"}}}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/xrpc/com.atproto.repo.createRecord") {
                        val body = call.receiveStream().readBytes().decodeToString()
                        createRecordBody = Json.parseToJsonElement(body).jsonObject
                        call.respondText(
                            "{" +
                                "\"uri\":\"at://did:plc:123/app.bsky.feed.post/1\",\"cid\":\"cid123\"}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                }
            }

            val blueskyClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val upload1 = uploadTestImage(blueskyClient, "flower1.jpeg", "rose")
            val upload2 = uploadTestImage(blueskyClient, "flower2.jpeg", "tulip")
            val linkPreview = LinkPreviewParser(httpClient = blueskyClient)

            val blueskyModule =
                BlueskyApiModule(
                    config =
                        BlueskyConfig(service = "http://localhost", username = "u", password = "p"),
                    filesModule = filesModule,
                    httpClient = blueskyClient,
                    linkPreviewParser = linkPreview,
                )

            val req =
                NewPostRequest(
                    content = "Hello bluesky",
                    images = listOf(upload1.uuid, upload2.uuid),
                )
            val result = blueskyModule.createPost(req)

            assertTrue(result.isRight())
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

            val record = requireNotNull(createRecordBody?.get("record")?.jsonObject)
            val embed = requireNotNull(record["embed"]?.jsonObject)

            val images = embed["images"] as JsonArray
            assertEquals(
                listOf("rose", "tulip"),
                images.map { it.jsonObject["alt"]?.jsonPrimitive?.content },
            )

            val ratios = images.map { it.jsonObject["aspectRatio"]?.jsonObject }
            assertEquals(
                uploadedImages[0].width,
                ratios[0]?.get("width")?.jsonPrimitive?.content?.toInt(),
            )
            assertEquals(
                uploadedImages[0].height,
                ratios[0]?.get("height")?.jsonPrimitive?.content?.toInt(),
            )
            assertEquals(
                uploadedImages[1].width,
                ratios[1]?.get("width")?.jsonPrimitive?.content?.toInt(),
            )
            assertEquals(
                uploadedImages[1].height,
                ratios[1]?.get("height")?.jsonPrimitive?.content?.toInt(),
            )

            blueskyClient.close()
        }
    }

    @Test
    fun `creates post with link preview`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            var createRecordBody: JsonObject? = null

            application {
                routing {
                    post("/xrpc/com.atproto.server.createSession") {
                        call.respondText(
                            "{" +
                                "\"accessJwt\":\"atk\",\"refreshJwt\":\"rft\",\"handle\":\"u\",\"did\":\"did:plc:123\"}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/xrpc/com.atproto.repo.createRecord") {
                        val body = call.receiveStream().readBytes().decodeToString()
                        createRecordBody = Json.parseToJsonElement(body).jsonObject
                        call.respondText(
                            "{" +
                                "\"uri\":\"at://did:plc:123/app.bsky.feed.post/1\",\"cid\":\"cid123\"}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    // Mock the link preview fetching
                    get("/test-page.html") {
                        call.respondText(
                            """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta property="og:title" content="Test Article">
                                <meta property="og:description" content="Test description">
                                <meta property="og:url" content="http://localhost/test-page.html">
                                <meta property="og:image" content="http://localhost/test-image.jpg">
                            </head>
                            <body></body>
                            </html>
                            """
                                .trimIndent(),
                            io.ktor.http.ContentType.Text.Html,
                        )
                    }
                    get("/test-image.jpg") {
                        // Return a minimal valid 1x1 JPEG image
                        val bytes =
                            byteArrayOf(
                                0xFF.toByte(),
                                0xD8.toByte(), // JPEG SOI marker
                                0xFF.toByte(),
                                0xE0.toByte(), // JFIF APP0 marker
                                0x00,
                                0x10, // Length
                                0x4A,
                                0x46,
                                0x49,
                                0x46, // "JFIF"
                                0x00, // Null terminator
                                0x01,
                                0x01, // Version 1.1
                                0x00, // No units
                                0x00,
                                0x01, // X density
                                0x00,
                                0x01, // Y density
                                0x00,
                                0x00, // No thumbnail
                                0xFF.toByte(),
                                0xD9.toByte(), // JPEG EOI marker
                            )
                        call.respondBytes(bytes, io.ktor.http.ContentType.Image.JPEG)
                    }
                    post("/xrpc/com.atproto.repo.uploadBlob") {
                        call.respondText(
                            "{" + "\"blob\":{\"ref\":{\"link\":\"bafytest\"}}}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                }
            }

            val blueskyClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val linkPreview = LinkPreviewParser(httpClient = blueskyClient)
            val blueskyModule =
                BlueskyApiModule(
                    config =
                        BlueskyConfig(service = "http://localhost", username = "u", password = "p"),
                    filesModule = filesModule,
                    httpClient = blueskyClient,
                    linkPreviewParser = linkPreview,
                )

            val req =
                NewPostRequest(
                    content = "Check out this article",
                    link = "http://localhost/test-page.html",
                )
            val result = blueskyModule.createPost(req)

            assertTrue(result.isRight())

            val record = requireNotNull(createRecordBody?.get("record")?.jsonObject)
            val embed = record["embed"]?.jsonObject

            assertNotNull(embed)
            assertEquals("app.bsky.embed.external", embed["\$type"]?.jsonPrimitive?.content)

            val external = embed["external"]?.jsonObject
            assertNotNull(external)
            assertEquals("http://localhost/test-page.html", external["uri"]?.jsonPrimitive?.content)
            assertEquals("Test Article", external["title"]?.jsonPrimitive?.content)
            assertEquals("Test description", external["description"]?.jsonPrimitive?.content)

            // Check that the thumbnail blob was included
            val thumb = external["thumb"]?.jsonObject
            assertNotNull(thumb)

            blueskyClient.close()
        }
    }
}
