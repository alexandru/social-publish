package com.alexn.socialpublish.modules

import com.alexn.socialpublish.testutils.createFilesModule
import com.alexn.socialpublish.testutils.createTestDatabase
import com.alexn.socialpublish.testutils.imageDimensions
import com.alexn.socialpublish.testutils.loadTestResourceBytes
import com.alexn.socialpublish.testutils.uploadTestImage
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

class FilesModuleTest {
    @Test
    fun `uploads images and resizes when needed`(
        @TempDir tempDir: Path,
    ) = testApplication {
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

        val client =
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

        val upload1 = uploadTestImage(client, "flower1.jpeg", "rose")
        val upload2 = uploadTestImage(client, "flower2.jpeg", "tulip")

        val processed1 = requireNotNull(filesModule.readImageFile(upload1.uuid))
        val processed2 = requireNotNull(filesModule.readImageFile(upload2.uuid))

        assertEquals("rose", processed1.altText)
        assertEquals("tulip", processed2.altText)

        val original1 = imageDimensions(loadTestResourceBytes("flower1.jpeg"))
        val original2 = imageDimensions(loadTestResourceBytes("flower2.jpeg"))
        val resized1 = imageDimensions(processed1.bytes)
        val resized2 = imageDimensions(processed2.bytes)

        assertTrue(resized1.width <= 1920)
        assertTrue(resized1.height <= 1080)
        assertTrue(resized2.width <= 1920)
        assertTrue(resized2.height <= 1080)

        assertTrue(resized1.width < original1.width || resized1.height < original1.height)
        assertTrue(resized2.width < original2.width || resized2.height < original2.height)

        assertEquals(resized1.width, processed1.width)
        assertEquals(resized1.height, processed1.height)
        assertEquals(resized2.width, processed2.width)
        assertEquals(resized2.height, processed2.height)

        client.close()
    }
}
