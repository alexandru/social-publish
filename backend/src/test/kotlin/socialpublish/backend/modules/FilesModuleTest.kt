package socialpublish.backend.modules

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase
import socialpublish.backend.testutils.imageDimensions
import socialpublish.backend.testutils.loadTestResourceBytes
import socialpublish.backend.testutils.uploadTestImage

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

        assertEquals(original1.width, stored1.width)
        assertEquals(original1.height, stored1.height)
        assertEquals(original2.width, stored2.width)
        assertEquals(original2.height, stored2.height)

        assertEquals(original1.width, processed1.width)
        assertEquals(original1.height, processed1.height)
        assertEquals(original2.width, processed2.width)
        assertEquals(original2.height, processed2.height)

        val uploadRow = requireNotNull(filesDb.getFileByUuid(upload1.uuid))
        val resized =
            requireNotNull(
                filesModule.readImageFile(upload1.uuid, maxWidth = 1920, maxHeight = 1080)
            )
        val resizedDimensions = imageDimensions(resized.bytes)

        assertTrue(resizedDimensions.width <= 1920)
        assertTrue(resizedDimensions.height <= 1080)
        assertTrue(
            resizedDimensions.width < original1.width || resizedDimensions.height < original1.height
        )
        assertEquals(resizedDimensions.width, resized.width)
        assertEquals(resizedDimensions.height, resized.height)

        val resizedFile =
            tempDir.resolve("uploads").resolve("resizing").resolve(uploadRow.hash).toFile()
        assertTrue(resizedFile.exists())

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
