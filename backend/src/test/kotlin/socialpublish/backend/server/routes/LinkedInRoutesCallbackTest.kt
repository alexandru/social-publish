package socialpublish.backend.server.routes

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.linkedin.LinkedInApiModule
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.clients.linkpreview.LinkPreviewParser
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase

class LinkedInRoutesCallbackTest {
    private val testUserUuid =
        UUIDv7.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `callback with missing code redirects with error`(
        @TempDir tempDir: Path
    ) = testApplication {
        val routes = createRoutes(tempDir)

        application {
            routing {
                get("/api/linkedin/callback") {
                    routes.callbackRoute(
                        testUserUuid,
                        LinkedInConfig(
                            clientId = "client-id",
                            clientSecret = "secret",
                        ),
                        call,
                    )
                }
            }
        }

        val response =
            createClient { followRedirects = false }
                .get("/api/linkedin/callback")

        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(
            response.headers[HttpHeaders.Location]?.startsWith(
                "/account?error="
            ) == true
        )
    }

    @Test
    fun `callback with missing state redirects with verification error`(
        @TempDir tempDir: Path
    ) = testApplication {
        val routes = createRoutes(tempDir)

        application {
            routing {
                get("/api/linkedin/callback") {
                    routes.callbackRoute(
                        testUserUuid,
                        LinkedInConfig(
                            clientId = "client-id",
                            clientSecret = "secret",
                        ),
                        call,
                    )
                }
            }
        }

        val response =
            createClient { followRedirects = false }
                .get("/api/linkedin/callback?code=abc")

        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(
            response.headers[HttpHeaders.Location]?.startsWith(
                "/account?error="
            ) == true
        )
    }

    @Test
    fun `callback with mismatched state redirects with verification error`(
        @TempDir tempDir: Path
    ) = testApplication {
        val routes = createRoutes(tempDir)

        application {
            routing {
                get("/api/linkedin/callback") {
                    routes.callbackRoute(
                        testUserUuid,
                        LinkedInConfig(
                            clientId = "client-id",
                            clientSecret = "secret",
                        ),
                        call,
                    )
                }
            }
        }

        val response =
            createClient { followRedirects = false }
                .get("/api/linkedin/callback?code=abc&state=callback-state")

        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(
            response.headers[HttpHeaders.Location]?.startsWith(
                "/account?error="
            ) == true
        )
    }

    private suspend fun ApplicationTestBuilder.createRoutes(
        tempDir: Path
    ): LinkedInRoutes {
        val db = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, db)
        val documentsDb = DocumentsDatabase(db)
        val linkedInClient = createClient {
            install(ClientContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
        }
        val linkPreview = LinkPreviewParser(httpClient = linkedInClient)
        val linkedInModule =
            LinkedInApiModule(
                "http://localhost",
                documentsDb,
                filesModule,
                linkedInClient.engine,
                linkPreview,
            )
        return LinkedInRoutes(linkedInModule, documentsDb)
    }
}
