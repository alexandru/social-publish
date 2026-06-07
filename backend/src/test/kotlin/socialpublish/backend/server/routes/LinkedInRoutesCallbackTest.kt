package socialpublish.backend.server.routes

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.assertNotNull
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
import socialpublish.backend.testutils.createTestSession

class LinkedInRoutesCallbackTest {
    private val testUserUuid =
        UUIDv7.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `callback with missing state redirects with error and clears state cookie`(
        @TempDir tempDir: Path
    ) = testApplication {
        val routes = createRoutes(tempDir)
        val config =
            LinkedInConfig(clientId = "client-id", clientSecret = "secret")

        application {
            routing {
                get("/api/linkedin/callback") {
                    context(createTestSession(testUserUuid)) {
                        routes.callbackRoute(config, call)
                    }
                }
            }
        }

        val response =
            createClient { followRedirects = false }
                .get("/api/linkedin/callback?code=abc") {
                    header(
                        HttpHeaders.Cookie,
                        "linkedin-oauth-state=expected-state",
                    )
                }

        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(
            response.headers[HttpHeaders.Location]?.startsWith(
                "/account?error="
            ) == true
        )
        val clearedCookie =
            response.headers.getAll(HttpHeaders.SetCookie)?.singleOrNull {
                it.startsWith("linkedin-oauth-state=")
            }
        assertNotNull(clearedCookie)
        assertTrue(clearedCookie.contains("Max-Age=0"))
        assertTrue(clearedCookie.contains("Path=/"))
        assertTrue(clearedCookie.contains("HttpOnly"))
        assertTrue(clearedCookie.contains("SameSite=Lax"))
    }

    @Test
    fun `callback with mismatched state redirects with verification error`(
        @TempDir tempDir: Path
    ) = testApplication {
        val routes = createRoutes(tempDir)
        val config =
            LinkedInConfig(clientId = "client-id", clientSecret = "secret")

        application {
            routing {
                get("/api/linkedin/callback") {
                    context(createTestSession(testUserUuid)) {
                        routes.callbackRoute(config, call)
                    }
                }
            }
        }

        val response =
            createClient { followRedirects = false }
                .get("/api/linkedin/callback?code=abc&state=callback-state") {
                    header(
                        HttpHeaders.Cookie,
                        "linkedin-oauth-state=cookie-state",
                    )
                }

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
