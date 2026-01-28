package socialpublish.backend.server.routes

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase
import socialpublish.backend.testutils.loadTestResourceBytes
import socialpublish.backend.testutils.uploadTestImage

class FilesRoutesTest {
    @Test
    fun `upload route processes file uploads`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)
        val filesRoutes = FilesRoutes(filesModule)

        application { routing { post("/api/files/upload") { filesRoutes.uploadFileRoute(call) } } }

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

        val response = uploadTestImage(client, "flower1.jpeg", "rose")
        assertTrue(response.uuid.isNotBlank())

        client.close()
    }

    @Test
    fun `fails when file part name is incorrect`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)
        val filesRoutes = FilesRoutes(filesModule)

        application { routing { post("/api/files/upload") { filesRoutes.uploadFileRoute(call) } } }

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

    @Test
    fun `get route returns stored file`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)
        val filesRoutes = FilesRoutes(filesModule)

        application {
            routing {
                post("/api/files/upload") { filesRoutes.uploadFileRoute(call) }
                get("/files/{uuid}") { filesRoutes.getFileRoute(call) }
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

        val upload = uploadTestImage(client, "flower1.jpeg", "rose")
        val response = client.get("/files/${upload.uuid}")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType])
        assertTrue(
            response.headers[HttpHeaders.ContentDisposition]?.contains("flower1.jpeg") == true
        )
        assertTrue(response.bodyAsBytes().isNotEmpty())

        client.close()
    }

    @Test
    fun `get route returns not found for unknown uuid`(@TempDir tempDir: Path) = testApplication {
        val jdbi = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, jdbi)
        val filesRoutes = FilesRoutes(filesModule)

        application { routing { get("/files/{uuid}") { filesRoutes.getFileRoute(call) } } }

        val response = client.get("/files/missing-uuid")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("File not found"))
    }
}
