package socialpublish.backend.modules

import arrow.core.getOrElse
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.testutils.*

class FilesModuleTest {
    @Test
    fun `uploads images and stores originals`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)
        val filesDb = socialpublish.backend.db.FilesDatabase(jdbi)

        application {
            routing {
                post("/api/files/upload") {
                    val result = filesModule.uploadFile(call)
                    when (result) {
                        is arrow.core.Either.Right ->
                            call.respondText(
                                Json.encodeToString(result.value),
                                io.ktor.http.ContentType.Application.Json,
                            )
                        is arrow.core.Either.Left ->
                            call.respondText(
                                Json.encodeToString(mapOf("error" to result.value.errorMessage)),
                                io.ktor.http.ContentType.Application.Json,
                                HttpStatusCode.fromValue(result.value.status),
                            )
                    }
                }
            }
        }

        val client = createClient {
            install(ClientContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
        }

        val upload1 = uploadTestImage(client, "flower1.jpeg", "rose")
        val upload2 = uploadTestImage(client, "flower2.jpeg", "tulip")

        val processed1 = requireNotNull(filesModule.readImageFile(upload1.uuid))
        val processed2 = requireNotNull(filesModule.readImageFile(upload2.uuid))

        assertEquals("rose", processed1.altText)
        assertEquals("tulip", processed2.altText)

        val original1 = imageDimensions(loadTestResourceBytes("flower1.jpeg"))
        val original2 = imageDimensions(loadTestResourceBytes("flower2.jpeg"))
        val stored1 = imageDimensions(processed1.bytes)
        val stored2 = imageDimensions(processed2.bytes)

        // Images should be optimized on upload (max 1600x1600)
        assertTrue(stored1.width <= 1600)
        assertTrue(stored1.height <= 1600)
        assertTrue(stored2.width <= 1600)
        assertTrue(stored2.height <= 1600)

        // Dimensions should match what's stored in the database
        assertEquals(stored1.width, processed1.width)
        assertEquals(stored1.height, processed1.height)
        assertEquals(stored2.width, processed2.width)
        assertEquals(stored2.height, processed2.height)

        // Verify readImageFile returns the same optimized image
        val uploadRow = requireNotNull(filesDb.getFileByUuid(upload1.uuid).getOrElse { throw it })
        val retrieved = requireNotNull(filesModule.readImageFile(upload1.uuid))
        val retrievedDimensions = imageDimensions(retrieved.bytes)

        assertEquals(stored1.width, retrievedDimensions.width)
        assertEquals(stored1.height, retrievedDimensions.height)
        assertEquals(retrievedDimensions.width, retrieved.width)
        assertEquals(retrievedDimensions.height, retrieved.height)

        client.close()
    }

    @Test
    fun `fails when file part name is incorrect`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)

        application {
            routing {
                post("/api/files/upload") {
                    val result = filesModule.uploadFile(call)
                    when (result) {
                        is arrow.core.Either.Right ->
                            call.respondText(
                                Json.encodeToString(result.value),
                                io.ktor.http.ContentType.Application.Json,
                            )
                        is arrow.core.Either.Left ->
                            call.respondText(
                                Json.encodeToString(mapOf("error" to result.value.errorMessage)),
                                io.ktor.http.ContentType.Application.Json,
                                HttpStatusCode.fromValue(result.value.status),
                            )
                    }
                }
            }
        }

        val client = createClient {
            install(ClientContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
        }

        val response =
            client.submitFormWithBinaryData(
                url = "/api/files/upload",
                formData =
                    io.ktor.client.request.forms.formData {
                        append(
                            "wrongName",
                            loadTestResourceBytes("flower1.jpeg"),
                            io.ktor.http.Headers.build {
                                append(io.ktor.http.HttpHeaders.ContentType, "image/jpeg")
                                append(
                                    io.ktor.http.HttpHeaders.ContentDisposition,
                                    "filename=\"flower1.jpeg\"",
                                )
                            },
                        )
                    },
            )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Missing file in upload"))

        client.close()
    }
}
