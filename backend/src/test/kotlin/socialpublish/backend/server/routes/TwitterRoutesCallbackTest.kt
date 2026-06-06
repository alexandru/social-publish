package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import socialpublish.backend.clients.twitter.TwitterApiModule
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.clients.twitter.TwitterOAuthDocument
import socialpublish.backend.clients.twitter.TwitterOAuthRequestToken
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.testutils.createFilesModule
import socialpublish.backend.testutils.createTestDatabase

class TwitterRoutesCallbackTest {
    private val testUserUuid =
        UUIDv7.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `callback with missing params redirects with error`(
        @TempDir tempDir: Path
    ) = testApplication {
        val testContext = createRoutes(tempDir)
        application {
            routing {
                get("/api/twitter/callback") {
                    testContext.routes.callbackRoute(
                        testUserUuid,
                        testContext.config,
                        call,
                    )
                }
            }
        }

        val response =
            createClient { followRedirects = false }
                .get("/api/twitter/callback?oauth_token=req123")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(
            "/account?error=Twitter+authorization+was+incomplete.+Please+try+again.",
            response.headers[HttpHeaders.Location],
        )
    }

    @Test
    fun `callback without pending request redirects with error`(
        @TempDir tempDir: Path
    ) = testApplication {
        val testContext = createRoutes(tempDir)
        application {
            routing {
                get("/api/twitter/callback") {
                    testContext.routes.callbackRoute(
                        testUserUuid,
                        testContext.config,
                        call,
                    )
                }
            }
        }

        val response =
            createClient { followRedirects = false }
                .get(
                    "/api/twitter/callback?oauth_token=req123&oauth_verifier=verifier"
                )

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(
            "/account?error=Twitter+authorization+failed.+Please+try+again.",
            response.headers[HttpHeaders.Location],
        )
    }

    @Test
    fun `callback exchanges stored pending request and clears it`(
        @TempDir tempDir: Path
    ) = testApplication {
        val oauthPort = ServerSocket(0).use { it.localPort }
        val oauthServer =
            embeddedServer(CIO, port = oauthPort) {
                    routing {
                        post("/oauth/access_token") {
                            call.respondText(
                                "oauth_token=access&oauth_token_secret=secret"
                            )
                        }
                    }
                }
                .start(wait = false)
        val oauthBaseUrl = "http://localhost:$oauthPort"
        val testContext = createRoutes(tempDir, oauthBaseUrl)
        val _ =
            testContext.documentsDb
                .createOrUpdate(
                    kind = "twitter-oauth-token",
                    payload =
                        TwitterOAuthDocument(
                                pendingRequest =
                                    TwitterOAuthRequestToken(
                                        token = "req123",
                                        secret = "secret123",
                                    )
                            )
                            .toJson(),
                    userUuid = testUserUuid,
                    searchKey = "twitter-oauth-token:$testUserUuid",
                )
                .getOrElse { throw it }

        application {
            routing {
                get("/api/twitter/callback") {
                    testContext.routes.callbackRoute(
                        testUserUuid,
                        testContext.config,
                        call,
                    )
                }
            }
        }

        val response =
            createClient { followRedirects = false }
                .get(
                    "/api/twitter/callback?oauth_token=req123&oauth_verifier=verifier"
                )

        assertEquals(HttpStatusCode.Found, response.status)
        oauthServer.stop()
        assertEquals("/account", response.headers[HttpHeaders.Location])
        val stored =
            testContext.documentsDb
                .searchByKey("twitter-oauth-token:$testUserUuid", testUserUuid)
                .getOrNull()
        val document =
            TwitterOAuthDocument.parse(stored?.payload ?: "").getOrNull()
        assertEquals("access", document?.accessToken?.key)
        assertNull(document?.pendingRequest)
    }

    private suspend fun ApplicationTestBuilder.createRoutes(
        tempDir: Path,
        oauthBaseUrl: String = "http://localhost",
    ): TwitterRouteTestContext {
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
            TwitterApiModule(
                "http://localhost",
                documentsDb,
                filesModule,
                twitterClient,
            )
        val config =
            TwitterConfig(
                oauth1ConsumerKey = "key",
                oauth1ConsumerSecret = "secret",
                oauthRequestTokenUrl = "$oauthBaseUrl/oauth/request_token",
                oauthAccessTokenUrl = "$oauthBaseUrl/oauth/access_token",
                oauthAuthorizeUrl = "$oauthBaseUrl/oauth/authorize",
            )
        return TwitterRouteTestContext(
            routes = TwitterRoutes(twitterModule, documentsDb),
            config = config,
            documentsDb = documentsDb,
        )
    }

    private data class TwitterRouteTestContext(
        val routes: TwitterRoutes,
        val config: TwitterConfig,
        val documentsDb: DocumentsDatabase,
    )
}
