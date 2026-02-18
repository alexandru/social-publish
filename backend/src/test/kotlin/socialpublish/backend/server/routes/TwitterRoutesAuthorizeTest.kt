package socialpublish.backend.server.routes

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.twitter.TwitterApiModule
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase

class TwitterRoutesAuthorizeTest {
    private val testUserUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val callbackJwtToken = "trusted-callback-jwt"

    @Test
    fun `authorize uses provided callback jwt token`(@TempDir tempDir: Path) = testApplication {
        val db = createTestDatabase(tempDir)
        val filesModule = createFilesModule(tempDir, db)
        val documentsDb = DocumentsDatabase(db)

        val twitterClient = createClient {
            install(ClientContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
        }

        val twitterModule =
            TwitterApiModule("http://localhost", documentsDb, filesModule, twitterClient)
        val routes = TwitterRoutes(twitterModule, documentsDb)
        val config =
            TwitterConfig(
                oauth1ConsumerKey = "k",
                oauth1ConsumerSecret = "s",
                oauthRequestTokenUrl = "http://localhost/oauth/request_token",
                oauthAuthorizeUrl = "http://localhost/oauth/authorize",
            )

        application {
            routing {
                post("/oauth/request_token") {
                    call.respondText(
                        "oauth_token=req123&oauth_token_secret=secret123&oauth_callback_confirmed=true"
                    )
                }
                get("/api/twitter/authorize") {
                    routes.authorizeRoute(testUserUuid, config, callbackJwtToken, call)
                }
            }
        }

        val response = client.get("/api/twitter/authorize")

        assertTrue(
            response.status != HttpStatusCode.Unauthorized,
            "Expected authorize flow to succeed, got ${response.status}",
        )
    }

    @Test
    fun `authorize ignores request access_token query override`(@TempDir tempDir: Path) =
        testApplication {
            val db = createTestDatabase(tempDir)
            val filesModule = createFilesModule(tempDir, db)
            val documentsDb = DocumentsDatabase(db)

            val twitterClient = createClient {
                install(ClientContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val twitterModule =
                TwitterApiModule("http://localhost", documentsDb, filesModule, twitterClient)
            val routes = TwitterRoutes(twitterModule, documentsDb)
            val config =
                TwitterConfig(
                    oauth1ConsumerKey = "k",
                    oauth1ConsumerSecret = "s",
                    oauthRequestTokenUrl = "http://localhost/oauth/request_token",
                    oauthAuthorizeUrl = "http://localhost/oauth/authorize",
                )

            application {
                routing {
                    post("/oauth/request_token") {
                        call.respondText(
                            "oauth_token=req123&oauth_token_secret=secret123&oauth_callback_confirmed=true"
                        )
                    }
                    get("/api/twitter/authorize") {
                        routes.authorizeRoute(testUserUuid, config, callbackJwtToken, call)
                    }
                }
            }

            val queryResponse =
                client.get("/api/twitter/authorize?access_token=attacker-controlled-token")
            assertTrue(
                queryResponse.status != HttpStatusCode.Unauthorized,
                "Expected authorize flow to succeed, got ${queryResponse.status}",
            )
            assertFalse(
                queryResponse.status == HttpStatusCode.Unauthorized,
                "Expected query param to be ignored for route auth token selection",
            )
        }
}
