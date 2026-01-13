package com.alexn.socialpublish.integrations

import arrow.core.Either
import com.alexn.socialpublish.FilesConfig
import com.alexn.socialpublish.db.Database
import com.alexn.socialpublish.db.FilesDatabase
import com.alexn.socialpublish.db.UploadPayload
import com.alexn.socialpublish.integrations.mastodon.MastodonApiModule
import com.alexn.socialpublish.integrations.mastodon.MastodonConfig
import com.alexn.socialpublish.models.NewMastodonPostResponse
import com.alexn.socialpublish.models.NewPostRequest
import com.alexn.socialpublish.modules.FilesModule
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

class MastodonApiTest {
    @Test
    fun `uploads image and creates status`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val dbPath = tempDir.resolve("test.db").toString()
        val jdbi = Jdbi.create("jdbc:sqlite:$dbPath").installPlugin(KotlinPlugin())
        Database.migrate(jdbi)

        val filesDb = FilesDatabase(jdbi)
        val uploadsDir = tempDir.resolve("uploads")
        uploadsDir.createDirectories()
        val filesConfig = FilesConfig(uploadedFilesPath = uploadsDir.toString(), baseUrl = "http://localhost")
        val filesModule = FilesModule(filesConfig, filesDb)

        application {
            routing {
                post("/api/v2/media") {
                    call.respondText(
                        "{" + "\"id\":\"media-1\",\"url\":\"http://media.local/1\"}",
                        io.ktor.http.ContentType.Application.Json,
                    )
                }
                post("/api/v1/statuses") {
                    call.respondText(
                        "{" + "\"id\":\"status-1\",\"uri\":\"http://mastodon.local/status/1\",\"url\":\"http://mastodon.local/s/1\"}",
                        io.ktor.http.ContentType.Application.Json,
                    )
                }
                get("/api/v1/media/{id}") {
                    val id = call.parameters["id"]
                    call.respondText(
                        "{" + "\"id\":\"$id\",\"url\":\"http://media.local/1\"}",
                        io.ktor.http.ContentType.Application.Json,
                    )
                }
            }
        }

        val mastodonClient =
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

        val mastodonModule =
            MastodonApiModule(MastodonConfig(host = "http://localhost", accessToken = "token"), filesModule, mastodonClient)

        val payload =
            UploadPayload(
                hash = "hash1",
                originalname = "img.jpg",
                mimetype = "image/jpeg",
                size = 10,
                altText = "alt",
                imageWidth = 10,
                imageHeight = 10,
            )
        val upload = filesDb.createFile(payload)
        val processedDir = uploadsDir.resolve("processed")
        processedDir.createDirectories()
        java.io.File(processedDir.toFile(), upload.hash).writeBytes(ByteArray(10) { 1 })

        val req = NewPostRequest(content = "Hello", images = listOf(upload.uuid))
        val result = runBlocking { mastodonModule.createPost(req) }

        assertTrue(result.isRight())
        val response = (result as Either.Right).value as NewMastodonPostResponse
        assertNotNull(response.uri)

        mastodonClient.close()
    }
}
