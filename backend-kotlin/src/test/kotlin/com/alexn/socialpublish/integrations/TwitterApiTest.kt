package com.alexn.socialpublish.integrations

import arrow.core.Either
import com.alexn.socialpublish.FilesConfig
import com.alexn.socialpublish.db.Database
import com.alexn.socialpublish.integrations.twitter.TwitterApiModule
import com.alexn.socialpublish.integrations.twitter.TwitterConfig
import com.alexn.socialpublish.models.NewPostRequest
import com.alexn.socialpublish.modules.FilesModule
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

class TwitterApiTest {
    @Test
    fun `uploads media and creates tweet`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val dbPath = tempDir.resolve("test.db").toString()
        val jdbi = Jdbi.create("jdbc:sqlite:$dbPath").installPlugin(KotlinPlugin())
        Database.migrate(jdbi)

        val filesConfig = FilesConfig(uploadedFilesPath = tempDir.resolve("uploads").toString(), baseUrl = "http://localhost")
        val filesModule = FilesModule(filesConfig, com.alexn.socialpublish.db.FilesDatabase(jdbi))

        application {
            routing {
                post("/1.1/media/upload.json") {
                    call.respondText("{" + "\"media_id_string\":\"mid123\"}", io.ktor.http.ContentType.Application.Json)
                }
                post("/2/tweets") {
                    call.respondText(
                        "{" + "\"data\":{\"id\":\"tweet123\",\"text\":\"ok\"}}",
                        io.ktor.http.ContentType.Application.Json,
                        io.ktor.http.HttpStatusCode.Created,
                    )
                }
                post("/1.1/media/metadata/create.json") {
                    call.respondText("{" + "\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                }
                post("/oauth/request_token") {
                    call.respondText("https://api.twitter.com/oauth/authorize?oauth_token=req123")
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
                kotlinx.serialization.json.Json.encodeToString(
                    com.alexn.socialpublish.integrations.twitter.TwitterOAuthToken.serializer(),
                    com.alexn.socialpublish.integrations.twitter.TwitterOAuthToken(key = "tok", secret = "sec"),
                ),
            searchKey = "twitter-oauth-token",
            tags = emptyList(),
        )

        val req = NewPostRequest(content = "Hello twitter")
        val result = runBlocking { twitterModule.createPost(req) }
        assertTrue(result.isRight())
        (result as Either.Right).value

        twitterClient.close()
    }
}
