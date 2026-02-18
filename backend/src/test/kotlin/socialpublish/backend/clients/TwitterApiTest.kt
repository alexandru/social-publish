package socialpublish.backend.clients

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.twitter.TwitterApiModule
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.server.routes.FilesRoutes
import socialpublish.backend.server.routes.TwitterRoutes
import socialpublish.backend.testutils.ImageDimensions
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase
import socialpublish.backend.testutils.imageDimensions
import socialpublish.backend.testutils.loadTestResourceBytes
import socialpublish.backend.testutils.receiveMultipart
import socialpublish.backend.testutils.uploadTestImage

class TwitterApiTest {
    @Test
    fun `uploads media with alt text and creates tweet`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val filesRoutes = FilesRoutes(filesModule)
            val uploadedImages = mutableListOf<ImageDimensions>()
            val altTextRequests = mutableListOf<Pair<String, String>>()
            var tweetMediaIds: List<String>? = null
            var mediaCounter = 0

            application {
                routing {
                    post("/api/files/upload") { filesRoutes.uploadFileRoute(call) }
                    post("/1.1/media/upload.json") {
                        val multipart = receiveMultipart(call)
                        val file = multipart.files.single()
                        uploadedImages.add(imageDimensions(file.bytes))
                        val mediaId = "mid${++mediaCounter}"
                        call.respondText(
                            "{" + "\"media_id_string\":\"$mediaId\"}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/2/tweets") {
                        val body = call.receiveStream().readBytes().decodeToString()
                        val payload = Json.parseToJsonElement(body).jsonObject
                        tweetMediaIds =
                            payload["media"]?.jsonObject?.get("media_ids")?.jsonArray?.map {
                                it.jsonPrimitive.content
                            }
                        call.respondText(
                            "{" + "\"data\":{\"id\":\"tweet123\",\"text\":\"ok\"}}",
                            io.ktor.http.ContentType.Application.Json,
                            HttpStatusCode.Created,
                        )
                    }
                    post("/1.1/media/metadata/create.json") {
                        val body = call.receiveStream().readBytes().decodeToString()
                        val payload = Json.parseToJsonElement(body).jsonObject
                        val mediaId = payload["media_id"]?.jsonPrimitive?.content ?: ""
                        val altText =
                            payload["alt_text"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                                ?: ""
                        altTextRequests.add(mediaId to altText)
                        call.respondText(
                            "{" + "\"ok\":true}",
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    post("/oauth/request_token") {
                        call.respondText(
                            "oauth_token=req123&oauth_token_secret=secret123&oauth_callback_confirmed=true"
                        )
                    }
                    post("/oauth/access_token") {
                        call.respondText("oauth_token=tok&oauth_token_secret=sec")
                    }
                }
            }

            val twitterClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val twitterConfig =
                TwitterConfig(
                    oauth1ConsumerKey = "k",
                    oauth1ConsumerSecret = "s",
                    apiBase = "http://localhost",
                    uploadBase = "http://localhost",
                    oauthRequestTokenUrl = "http://localhost/oauth/request_token",
                    oauthAccessTokenUrl = "http://localhost/oauth/access_token",
                    oauthAuthorizeUrl = "http://localhost/oauth/authorize",
                )

            val documentsDb = socialpublish.backend.db.DocumentsDatabase(jdbi)
            val twitterModule =
                TwitterApiModule("http://localhost", documentsDb, filesModule, twitterClient)

            val _ =
                documentsDb.createOrUpdate(
                    kind = "twitter-oauth-token",
                    payload =
                        Json.encodeToString(
                            socialpublish.backend.clients.twitter.TwitterOAuthToken.serializer(),
                            socialpublish.backend.clients.twitter.TwitterOAuthToken(
                                key = "tok",
                                secret = "sec",
                            ),
                        ),
                    userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    searchKey = "twitter-oauth-token:00000000-0000-0000-0000-000000000001",
                    tags = emptyList(),
                )

            val upload1 = uploadTestImage(twitterClient, "flower1.jpeg", "rose")
            val upload2 = uploadTestImage(twitterClient, "flower2.jpeg", "tulip")

            val req =
                NewPostRequest(
                    content = "Hello twitter",
                    images = listOf(upload1.uuid, upload2.uuid),
                )
            val testUserUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
            val result = twitterModule.createPost(twitterConfig, req, testUserUuid)
            assertTrue(result.isRight())

            assertEquals(2, uploadedImages.size)
            assertEquals(listOf("mid1", "mid2"), tweetMediaIds)
            assertEquals(listOf("mid1" to "rose", "mid2" to "tulip"), altTextRequests)

            val original1 = imageDimensions(loadTestResourceBytes("flower1.jpeg"))
            val original2 = imageDimensions(loadTestResourceBytes("flower2.jpeg"))
            // Images are optimized on upload to max 1600x1600
            assertTrue(uploadedImages[0].width <= 1600)
            assertTrue(uploadedImages[0].height <= 1600)
            assertTrue(uploadedImages[1].width <= 1600)
            assertTrue(uploadedImages[1].height <= 1600)

            twitterClient.close()
        }
    }

    @Test
    fun `cleans up HTML properly`(@TempDir tempDir: Path) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            var tweetText: String? = null

            application {
                routing {
                    post("/2/tweets") {
                        val body = call.receiveStream().readBytes().decodeToString()
                        val payload = Json.parseToJsonElement(body).jsonObject
                        tweetText = payload["text"]?.jsonPrimitive?.content
                        call.respondText(
                            "{" + "\"data\":{\"id\":\"tweet123\",\"text\":\"ok\"}}",
                            io.ktor.http.ContentType.Application.Json,
                            HttpStatusCode.Created,
                        )
                    }
                    post("/oauth/request_token") {
                        call.respondText(
                            "oauth_token=req123&oauth_token_secret=secret123&oauth_callback_confirmed=true"
                        )
                    }
                    post("/oauth/access_token") {
                        call.respondText("oauth_token=tok&oauth_token_secret=sec")
                    }
                }
            }

            val twitterClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val twitterConfig =
                TwitterConfig(
                    oauth1ConsumerKey = "k",
                    oauth1ConsumerSecret = "s",
                    apiBase = "http://localhost",
                    uploadBase = "http://localhost",
                    oauthRequestTokenUrl = "http://localhost/oauth/request_token",
                    oauthAccessTokenUrl = "http://localhost/oauth/access_token",
                    oauthAuthorizeUrl = "http://localhost/oauth/authorize",
                )

            val documentsDb = socialpublish.backend.db.DocumentsDatabase(jdbi)
            val twitterModule =
                TwitterApiModule("http://localhost", documentsDb, filesModule, twitterClient)

            val _ =
                documentsDb.createOrUpdate(
                    kind = "twitter-oauth-token",
                    payload =
                        Json.encodeToString(
                            socialpublish.backend.clients.twitter.TwitterOAuthToken.serializer(),
                            socialpublish.backend.clients.twitter.TwitterOAuthToken(
                                key = "tok",
                                secret = "sec",
                            ),
                        ),
                    userUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    searchKey = "twitter-oauth-token:00000000-0000-0000-0000-000000000001",
                    tags = emptyList(),
                )

            val req =
                NewPostRequest(
                    content = "<p>Hello <strong>world</strong>!</p><p>Testing &amp; fun</p>",
                    cleanupHtml = true,
                )
            val testUserUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
            val result = twitterModule.createPost(twitterConfig, req, testUserUuid)
            assertTrue(result.isRight())

            // Jsoup properly decodes HTML entities and removes tags
            assertEquals("Hello world! Testing & fun", tweetText)

            twitterClient.close()
        }
    }

    @Test
    fun `status route returns serializable response with authorization`(@TempDir tempDir: Path) =
        runTest {
            testApplication {
                val jdbi = createTestDatabase(tempDir)
                val filesModule = createFilesModule(tempDir, jdbi)

                val twitterClient = createClient {
                    install(ClientContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                            }
                        )
                    }
                }
                val twitterConfig =
                    TwitterConfig(
                        oauth1ConsumerKey = "k",
                        oauth1ConsumerSecret = "s",
                        apiBase = "http://localhost",
                        uploadBase = "http://localhost",
                        oauthRequestTokenUrl = "http://localhost/oauth/request_token",
                        oauthAccessTokenUrl = "http://localhost/oauth/access_token",
                        oauthAuthorizeUrl = "http://localhost/oauth/authorize",
                    )

                val documentsDb = socialpublish.backend.db.DocumentsDatabase(jdbi)
                val twitterModule =
                    TwitterApiModule("http://localhost", documentsDb, filesModule, twitterClient)

                // Create a Twitter OAuth token in the database
                val _ =
                    documentsDb.createOrUpdate(
                        kind = "twitter-oauth-token",
                        payload =
                            Json.encodeToString(
                                socialpublish.backend.clients.twitter.TwitterOAuthToken
                                    .serializer(),
                                socialpublish.backend.clients.twitter.TwitterOAuthToken(
                                    key = "tok",
                                    secret = "sec",
                                ),
                            ),
                        userUuid =
                            java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        searchKey = "twitter-oauth-token:00000000-0000-0000-0000-000000000001",
                        tags = emptyList(),
                    )

                application {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                            }
                        )
                    }
                    routing {
                        get("/api/twitter/status") {
                            TwitterRoutes(twitterModule, documentsDb)
                                .statusRoute(
                                    java.util.UUID.fromString(
                                        "00000000-0000-0000-0000-000000000001"
                                    ),
                                    call,
                                )
                        }
                    }
                }

                // Make request and verify it doesn't throw serialization error
                val response = client.get("/api/twitter/status")

                assertEquals(HttpStatusCode.OK, response.status)

                val body = response.bodyAsText()
                val json = Json.parseToJsonElement(body).jsonObject

                // Verify response structure
                assertTrue(json["hasAuthorization"]?.jsonPrimitive?.content == "true")
                assertTrue(json["createdAt"]?.jsonPrimitive?.long != null)

                twitterClient.close()
            }
        }

    @Test
    fun `status route returns serializable response without authorization`(@TempDir tempDir: Path) =
        runTest {
            testApplication {
                val jdbi = createTestDatabase(tempDir)
                val filesModule = createFilesModule(tempDir, jdbi)

                val twitterClient = createClient {
                    install(ClientContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                            }
                        )
                    }
                }
                val twitterConfig =
                    TwitterConfig(
                        oauth1ConsumerKey = "k",
                        oauth1ConsumerSecret = "s",
                        apiBase = "http://localhost",
                        uploadBase = "http://localhost",
                        oauthRequestTokenUrl = "http://localhost/oauth/request_token",
                        oauthAccessTokenUrl = "http://localhost/oauth/access_token",
                        oauthAuthorizeUrl = "http://localhost/oauth/authorize",
                    )

                val documentsDb = socialpublish.backend.db.DocumentsDatabase(jdbi)
                val twitterModule =
                    TwitterApiModule("http://localhost", documentsDb, filesModule, twitterClient)

                application {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                            }
                        )
                    }
                    routing {
                        get("/api/twitter/status") {
                            TwitterRoutes(twitterModule, documentsDb)
                                .statusRoute(
                                    java.util.UUID.fromString(
                                        "00000000-0000-0000-0000-000000000001"
                                    ),
                                    call,
                                )
                        }
                    }
                }

                // Make request without any token in database
                val response = client.get("/api/twitter/status")

                assertEquals(HttpStatusCode.OK, response.status)

                val body = response.bodyAsText()
                val json = Json.parseToJsonElement(body).jsonObject

                // Verify response structure
                assertTrue(json["hasAuthorization"]?.jsonPrimitive?.content == "false")
                // createdAt should be null when no authorization exists
                assertTrue(json["createdAt"] == null || json["createdAt"]?.toString() == "null")

                twitterClient.close()
            }
        }
}
