package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.linkedin.LinkedInApiModule
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.clients.linkpreview.LinkPreviewParser
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase

class LinkedInRoutesAuthorizeTest {
    private val testUserUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val callbackJwtToken = "trusted-callback-jwt"

    @Test
    fun `authorize uses provided callback jwt token`(@TempDir tempDir: Path) = testApplication {
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
                    routes.authorizeRoute(testUserUuid, config, callbackJwtToken, call)
                }
            }
        }

        val response = client.get("/api/linkedin/authorize")

        assertTrue(
            response.status != HttpStatusCode.Unauthorized,
            "Expected authorize flow to succeed, got ${response.status}",
        )

        val states =
            documentsDb
                .getAllForUser(kind = "linkedin-oauth-state", userUuid = testUserUuid)
                .getOrElse { throw it }
        assertTrue(states.isNotEmpty())
        assertTrue(states.first().payload.contains("trusted-callback-jwt"))
    }

    @Test
    fun `authorize ignores request access_token query override`(@TempDir tempDir: Path) =
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

            application {
                routing {
                    get("/api/linkedin/authorize") {
                        routes.authorizeRoute(testUserUuid, config, callbackJwtToken, call)
                    }
                }
            }

            val queryResponse =
                client.get("/api/linkedin/authorize?access_token=attacker-controlled-token")
            assertTrue(
                queryResponse.status != HttpStatusCode.Unauthorized,
                "Expected authorize flow to succeed, got ${queryResponse.status}",
            )
            val states =
                documentsDb
                    .getAllForUser(kind = "linkedin-oauth-state", userUuid = testUserUuid)
                    .getOrElse { throw it }
            assertTrue(states.isNotEmpty())
            assertTrue(states.first().payload.contains("trusted-callback-jwt"))
            assertFalse(states.first().payload.contains("attacker-controlled-token"))
        }
}
