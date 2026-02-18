package socialpublish.backend.server.routes

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
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

    @Test
    fun `authorize accepts jwt from cookie`(@TempDir tempDir: Path) = testApplication {
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
                get("/api/linkedin/authorize") { routes.authorizeRoute(testUserUuid, config, call) }
            }
        }

        val response =
            client.get("/api/linkedin/authorize") {
                header(HttpHeaders.Cookie, "access_token=jwt-cookie-token")
            }

        assertTrue(
            response.status != HttpStatusCode.Unauthorized,
            "Expected cookie token to be accepted (non-401), got ${response.status}",
        )
    }

    @Test
    fun `authorize accepts jwt from query and authorization header`(@TempDir tempDir: Path) =
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
                        routes.authorizeRoute(testUserUuid, config, call)
                    }
                }
            }

            val queryResponse = client.get("/api/linkedin/authorize?access_token=jwt-query-token")
            assertTrue(
                queryResponse.status != HttpStatusCode.Unauthorized,
                "Expected query token to be accepted (non-401), got ${queryResponse.status}",
            )

            val headerResponse =
                client.get("/api/linkedin/authorize") {
                    header(HttpHeaders.Authorization, "Bearer jwt-header-token")
                }
            assertTrue(
                headerResponse.status != HttpStatusCode.Unauthorized,
                "Expected header token to be accepted (non-401), got ${headerResponse.status}",
            )
        }
}
