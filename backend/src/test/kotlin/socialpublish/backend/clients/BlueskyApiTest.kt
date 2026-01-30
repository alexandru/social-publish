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
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.server.routes.FilesRoutes
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
            val linkPreview =
                LinkPreviewParser(httpClient = blueskyClient, imageMagick = createTestImageMagick())
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
            val filesRoutes = FilesRoutes(filesModule)
            val uploadedImages = mutableListOf<ImageDimensions>()
            var createRecordBody: JsonObject? = null

            application {
                routing {
                    post("/api/files/upload") { filesRoutes.uploadFileRoute(call) }
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
            val linkPreview =
                LinkPreviewParser(httpClient = blueskyClient, imageMagick = createTestImageMagick())

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

            // Images are optimized on upload to max 1600x1600
            assertTrue(uploadedImages[0].width <= 1600)
            assertTrue(uploadedImages[0].height <= 1600)
            assertTrue(uploadedImages[1].width <= 1600)
            assertTrue(uploadedImages[1].height <= 1600)

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
            val linkPreview =
                LinkPreviewParser(httpClient = blueskyClient, imageMagick = createTestImageMagick())
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

    @Test
    fun `optimizes link preview images before upload`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            var uploadedImageSize: Int? = null

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
                    get("/test-page.html") {
                        call.respondText(
                            """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta property="og:title" content="Test Article">
                                <meta property="og:image" content="http://localhost/large-image.jpg">
                            </head>
                            <body></body>
                            </html>
                            """
                                .trimIndent(),
                            io.ktor.http.ContentType.Text.Html,
                        )
                    }
                    get("/large-image.jpg") {
                        // Return a real test image that might be large
                        val bytes = loadTestResourceBytes("flower1.jpeg")
                        call.respondBytes(bytes, io.ktor.http.ContentType.Image.JPEG)
                    }
                    post("/xrpc/com.atproto.repo.uploadBlob") {
                        val bytes = call.receiveStream().readBytes()
                        uploadedImageSize = bytes.size
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
            val linkPreview =
                LinkPreviewParser(httpClient = blueskyClient, imageMagick = createTestImageMagick())
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
            assertNotNull(uploadedImageSize)
            // Bluesky limit is approximately 976KB, ensure we're under that
            assertTrue(
                uploadedImageSize <= 976_000,
                "Image size $uploadedImageSize should be <= 976KB",
            )

            blueskyClient.close()
        }
    }

    @Test
    fun `shortens link text while preserving preview`(@TempDir tempDir: Path) = runTest {
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
                        val bytes =
                            byteArrayOf(
                                0xFF.toByte(),
                                0xD8.toByte(),
                                0xFF.toByte(),
                                0xE0.toByte(),
                                0x00,
                                0x10,
                                0x4A,
                                0x46,
                                0x49,
                                0x46,
                                0x00,
                                0x01,
                                0x01,
                                0x00,
                                0x00,
                                0x01,
                                0x00,
                                0x01,
                                0x00,
                                0x00,
                                0xFF.toByte(),
                                0xD9.toByte(),
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
            val linkPreview =
                LinkPreviewParser(httpClient = blueskyClient, imageMagick = createTestImageMagick())
            val blueskyModule =
                BlueskyApiModule(
                    config =
                        BlueskyConfig(service = "http://localhost", username = "u", password = "p"),
                    filesModule = filesModule,
                    httpClient = blueskyClient,
                    linkPreviewParser = linkPreview,
                )

            val longLink =
                "http://localhost/test-page.html?with=a-very-long-query-parameter-to-overflow"
            val req = NewPostRequest(content = "Check out this article", link = longLink)
            val result = blueskyModule.createPost(req)

            assertTrue(result.isRight())

            val record = requireNotNull(createRecordBody?.get("record")?.jsonObject)
            val text = requireNotNull(record["text"]?.jsonPrimitive?.content)
            val expectedDisplay = longLink.take(21) + "..."
            assertEquals("Check out this article\n\n$expectedDisplay", text)

            val facets = requireNotNull(record["facets"]?.jsonArray)
            val linkFacet = facets.first().jsonObject
            val index = linkFacet["index"]?.jsonObject
            val byteStart = index?.get("byteStart")?.jsonPrimitive?.int
            val byteEnd = index?.get("byteEnd")?.jsonPrimitive?.int
            val expectedStart = "Check out this article\n\n".toByteArray(Charsets.UTF_8).size
            val expectedEnd = expectedStart + expectedDisplay.toByteArray(Charsets.UTF_8).size

            assertEquals(expectedStart, byteStart)
            assertEquals(expectedEnd, byteEnd)

            val features = linkFacet["features"] as JsonArray
            val uri = features.first().jsonObject["uri"]?.jsonPrimitive?.content
            assertEquals(longLink, uri)

            val embed = record["embed"]?.jsonObject
            assertNotNull(embed)
            assertEquals("app.bsky.embed.external", embed["\$type"]?.jsonPrimitive?.content)

            blueskyClient.close()
        }
    }

    @Test
    fun `shortens multiple links in text`(@TempDir tempDir: Path) = runTest {
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
            val linkPreview =
                LinkPreviewParser(httpClient = blueskyClient, imageMagick = createTestImageMagick())
            val blueskyModule =
                BlueskyApiModule(
                    config =
                        BlueskyConfig(service = "http://localhost", username = "u", password = "p"),
                    filesModule = filesModule,
                    httpClient = blueskyClient,
                    linkPreviewParser = linkPreview,
                )

            val linkOne = "http://localhost/alpha?with=a-long-query-param"
            val linkTwo = "http://localhost/beta?another-long-query-param"
            val content = "First $linkOne and then $linkTwo"
            val req = NewPostRequest(content = content)
            val result = blueskyModule.createPost(req)

            assertTrue(result.isRight())

            val record = requireNotNull(createRecordBody?.get("record")?.jsonObject)
            val text = requireNotNull(record["text"]?.jsonPrimitive?.content)

            val displayOne = linkOne.take(21) + "..."
            val displayTwo = linkTwo.take(21) + "..."
            val expectedText = "First $displayOne and then $displayTwo"

            assertEquals(expectedText, text)

            val facets = requireNotNull(record["facets"]?.jsonArray)
            assertEquals(2, facets.size)

            val firstIndex = facets[0].jsonObject["index"]?.jsonObject
            val secondIndex = facets[1].jsonObject["index"]?.jsonObject

            val expectedFirstStart = "First ".toByteArray(Charsets.UTF_8).size
            val expectedFirstEnd = expectedFirstStart + displayOne.toByteArray(Charsets.UTF_8).size
            val expectedSecondStart = "First $displayOne and then ".toByteArray(Charsets.UTF_8).size
            val expectedSecondEnd =
                expectedSecondStart + displayTwo.toByteArray(Charsets.UTF_8).size

            assertEquals(expectedFirstStart, firstIndex?.get("byteStart")?.jsonPrimitive?.int)
            assertEquals(expectedFirstEnd, firstIndex?.get("byteEnd")?.jsonPrimitive?.int)
            assertEquals(expectedSecondStart, secondIndex?.get("byteStart")?.jsonPrimitive?.int)
            assertEquals(expectedSecondEnd, secondIndex?.get("byteEnd")?.jsonPrimitive?.int)

            val firstUri =
                facets[0]
                    .jsonObject["features"]
                    ?.jsonArray
                    ?.first()
                    ?.jsonObject
                    ?.get("uri")
                    ?.jsonPrimitive
                    ?.content
            val secondUri =
                facets[1]
                    .jsonObject["features"]
                    ?.jsonArray
                    ?.first()
                    ?.jsonObject
                    ?.get("uri")
                    ?.jsonPrimitive
                    ?.content

            assertEquals(linkOne, firstUri)
            assertEquals(linkTwo, secondUri)

            blueskyClient.close()
        }
    }
}
