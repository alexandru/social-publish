package com.alexn.socialpublish.integrations

import arrow.core.Either
import com.alexn.socialpublish.FilesConfig
import com.alexn.socialpublish.db.Database
import com.alexn.socialpublish.integrations.bluesky.BlueskyApiModule
import com.alexn.socialpublish.integrations.bluesky.BlueskyConfig
import com.alexn.socialpublish.models.NewPostRequest
import com.alexn.socialpublish.modules.FilesModule
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

class BlueskyApiTest {
    @Test
    fun `creates post without images`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val dbPath = tempDir.resolve("test.db").toString()
        val jdbi = Jdbi.create("jdbc:sqlite:$dbPath").installPlugin(KotlinPlugin())
        Database.migrate(jdbi)

        val filesConfig = FilesConfig(uploadedFilesPath = tempDir.resolve("uploads").toString(), baseUrl = "http://localhost")
        val filesModule = FilesModule(filesConfig, com.alexn.socialpublish.db.FilesDatabase(jdbi))

        application {
            routing {
                post("/xrpc/com.atproto.server.createSession") {
                    call.respondText(
                        "{" + "\"accessJwt\":\"atk\",\"refreshJwt\":\"rft\",\"handle\":\"u\",\"did\":\"did:plc:123\"}",
                        io.ktor.http.ContentType.Application.Json,
                    )
                }
                post("/xrpc/com.atproto.repo.uploadBlob") {
                    call.respondText("{" + "\"blob\":{\"ref\":{\"something\":\"ok\"}}}", io.ktor.http.ContentType.Application.Json)
                }
                post("/xrpc/com.atproto.repo.createRecord") {
                    call.respondText(
                        "{" + "\"uri\":\"at://did:plc:123/app.bsky.feed.post/1\",\"cid\":\"cid123\"}",
                        io.ktor.http.ContentType.Application.Json,
                    )
                }
            }
        }

        val blueskyClient =
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
        val blueskyModule =
            BlueskyApiModule(BlueskyConfig(service = "http://localhost", username = "u", password = "p"), filesModule, blueskyClient)

        val req = NewPostRequest(content = "Hello bluesky")
        val result = runBlocking { blueskyModule.createPost(req) }

        assertTrue(result.isRight())
        (result as Either.Right).value

        blueskyClient.close()
    }
}
