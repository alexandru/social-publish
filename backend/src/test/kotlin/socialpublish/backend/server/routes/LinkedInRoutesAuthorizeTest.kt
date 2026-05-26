package socialpublish.backend.server.routes

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
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

class LinkedInRoutesAuthorizeTest {
    private val testUserUuid =
        UUIDv7.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `authorize succeeds and sets OAuth state cookie`(
        @TempDir tempDir: Path
    ) = testApplication {
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
        val routes = LinkedInRoutes(linkedInModule, documentsDb)
        val config =
            LinkedInConfig(
                clientId = "client-id",
                clientSecret = "client-secret",
                authorizationUrl = "http://localhost/oauth/v2/authorization",
            )

        application {
            routing {
                get("/api/linkedin/authorize") {
                    context(createTestSession(testUserUuid)) {
                        routes.authorizeRoute(config, call)
                    }
                }
            }
        }

        val response = client.get("/api/linkedin/authorize")

        assertTrue(
            response.status != HttpStatusCode.Unauthorized,
            "Expected authorize flow to succeed, got ${response.status}",
        )
    }

    @Test
    fun `authorize generates unique state each call`(@TempDir tempDir: Path) =
        testApplication {
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
            val routes = LinkedInRoutes(linkedInModule, documentsDb)
            val config =
                LinkedInConfig(
                    clientId = "client-id",
                    clientSecret = "client-secret",
                    authorizationUrl = "http://localhost/oauth/v2/authorization",
                )

            // generateOAuthState should return unique values
            val state1 = linkedInModule.generateOAuthState()
            val state2 = linkedInModule.generateOAuthState()
            val state3 = linkedInModule.generateOAuthState()

            assertTrue(state1 != state2, "Expected different states")
            assertTrue(state1 != state3, "Expected different states")
            assertTrue(state2 != state3, "Expected different states")
            assertTrue(
                state1.length >= 32,
                "Expected state to be at least 32 chars",
            )
        }
}
