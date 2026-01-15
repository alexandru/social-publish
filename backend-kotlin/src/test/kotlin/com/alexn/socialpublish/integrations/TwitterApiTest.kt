package com.alexn.socialpublish.integrations

import arrow.core.Either
import com.alexn.socialpublish.integrations.twitter.TwitterApiModule
import com.alexn.socialpublish.integrations.twitter.TwitterConfig
import com.alexn.socialpublish.models.NewPostRequest
import com.alexn.socialpublish.testutils.ImageDimensions
import com.alexn.socialpublish.testutils.createFilesModule
import com.alexn.socialpublish.testutils.createTestDatabase
import com.alexn.socialpublish.testutils.imageDimensions
import com.alexn.socialpublish.testutils.loadTestResourceBytes
import com.alexn.socialpublish.testutils.receiveMultipart
import com.alexn.socialpublish.testutils.uploadTestImage
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

class TwitterApiTest {
    @Test
    fun `uploads media with alt text and creates tweet`(
        @TempDir tempDir: Path,
    ) = runTest {
        testApplication {
            val jdbi = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, jdbi)
            val uploadedImages = mutableListOf<ImageDimensions>()
            val altTextRequests = mutableListOf<Pair<String, String>>()
            var tweetMediaIds: List<String>? = null
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
                                    HttpStatusCode.fromValue(result.value.status),
                                )
                        }
                    }
                    post("/1.1/media/upload.json") {
                        val multipart = receiveMultipart(call)
                        val file = multipart.files.single()
                        uploadedImages.add(imageDimensions(file.bytes))
                        val mediaId = "mid${++mediaCounter}"
                        call.respondText("{" + "\"media_id_string\":\"$mediaId\"}", io.ktor.http.ContentType.Application.Json)
                    }
                    post("/2/tweets") {
                        val body = call.receiveStream().readBytes().decodeToString()
                        val payload = Json.parseToJsonElement(body).jsonObject
                        tweetMediaIds =
                            payload["media"]
                                ?.jsonObject
                                ?.get("media_ids")
                                ?.jsonArray
                                ?.map { it.jsonPrimitive.content }
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
                            payload["alt_text"]
                                ?.jsonObject
                                ?.get("text")
                                ?.jsonPrimitive
                                ?.content
                                ?: ""
                        altTextRequests.add(mediaId to altText)
                        call.respondText("{" + "\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                    }
                    post("/oauth/request_token") {
                        call.respondText("oauth_token=req123&oauth_token_secret=secret123&oauth_callback_confirmed=true")
                    }
                    post("/oauth/access_token") {
                        call.respondText("oauth_token=tok&oauth_token_secret=sec")
                    }
                }
            }

            val twitterClient =
                createClient {
                    install(ClientContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                            },
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

            val documentsDb = com.alexn.socialpublish.db.DocumentsDatabase(jdbi)
            val twitterModule = TwitterApiModule(twitterConfig, "http://localhost", documentsDb, filesModule, twitterClient)

            documentsDb.createOrUpdate(
                kind = "twitter-oauth-token",
                payload =
                    Json.encodeToString(
                        com.alexn.socialpublish.integrations.twitter.TwitterOAuthToken.serializer(),
                        com.alexn.socialpublish.integrations.twitter.TwitterOAuthToken(key = "tok", secret = "sec"),
                    ),
                searchKey = "twitter-oauth-token",
                tags = emptyList(),
            )

            val upload1 = uploadTestImage(twitterClient, "flower1.jpeg", "rose")
            val upload2 = uploadTestImage(twitterClient, "flower2.jpeg", "tulip")

            val req = NewPostRequest(content = "Hello twitter", images = listOf(upload1.uuid, upload2.uuid))
            val result = twitterModule.createPost(req)
            assertTrue(result.isRight())

            assertEquals(2, uploadedImages.size)
            assertEquals(listOf("mid1", "mid2"), tweetMediaIds)
            assertEquals(listOf("mid1" to "rose", "mid2" to "tulip"), altTextRequests)

            val original1 = imageDimensions(loadTestResourceBytes("flower1.jpeg"))
            val original2 = imageDimensions(loadTestResourceBytes("flower2.jpeg"))
            assertTrue(uploadedImages[0].width <= 1920)
            assertTrue(uploadedImages[0].height <= 1080)
            assertTrue(uploadedImages[1].width <= 1920)
            assertTrue(uploadedImages[1].height <= 1080)
            assertTrue(uploadedImages[0].width < original1.width || uploadedImages[0].height < original1.height)
            assertTrue(uploadedImages[1].width < original2.width || uploadedImages[1].height < original2.height)

            twitterClient.close()
        }
    }
}
