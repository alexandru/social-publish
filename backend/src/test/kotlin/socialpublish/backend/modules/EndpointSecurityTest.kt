package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.getOrElse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import socialpublish.backend.db.Database
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.UserSessionsDatabase
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.db.query
import socialpublish.backend.server.respondWithUnauthorized
import socialpublish.backend.server.routes.AuthRoutes

class EndpointSecurityTest {
    private data class TestContext(
        val db: Database,
        val usersDb: UsersDatabase,
        val userSessionsDb: UserSessionsDatabase,
        val authService: AuthService,
        val authRoutes: AuthRoutes,
        val token: String,
    )

    private suspend fun createTestContext(): TestContext {
        val db = Database.connectUnmanaged(":memory:")
        val usersDb = UsersDatabase(db)
        val userSessionsDb = UserSessionsDatabase(db, usersDb)
        val authService = AuthService(userSessionsDb)
        val authRoutes = AuthRoutes(authService, DocumentsDatabase(db))
        val _ = usersDb.createUser("secuser", "secpass").getOrElse { throw it }
        val loginResult = authService.login("secuser", "secpass").getOrNull()!!
        return TestContext(
            db,
            usersDb,
            userSessionsDb,
            authService,
            authRoutes,
            loginResult.rawToken,
        )
    }

    @Test
    fun `protected endpoint should reject requests without token`() {
        testApplication {
            val ctx = createTestContext()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/protected") {
                        val result = ctx.authRoutes.extractAccessToken(call)
                        if (result == null) {
                            call.respondWithUnauthorized()
                            return@get
                        }
                        call.respondText("Success")
                    }
                }
            }

            val response = client.get("/api/protected")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `protected endpoint should reject requests with invalid token`() {
        testApplication {
            val ctx = createTestContext()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/protected") {
                        val token = ctx.authRoutes.extractAccessToken(call)
                        if (token == null) {
                            call.respondWithUnauthorized()
                            return@get
                        }
                        val result = ctx.authService.authorize(token)
                        if (result is Either.Left) {
                            call.respondWithUnauthorized()
                            return@get
                        }
                        call.respondText("Success")
                    }
                }
            }

            val response =
                client.get("/api/protected") {
                    header(HttpHeaders.Authorization, "Bearer invalid-token-12345")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `protected endpoint should accept requests with valid session token`() {
        testApplication {
            val ctx = createTestContext()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/protected") {
                        val token = ctx.authRoutes.extractAccessToken(call)
                        if (token == null) {
                            call.respondWithUnauthorized()
                            return@get
                        }
                        val result = ctx.authService.authorize(token)
                        if (result is Either.Left) {
                            call.respondWithUnauthorized()
                            return@get
                        }
                        call.respondText("Success")
                    }
                }
            }

            val response =
                client.get("/api/protected") {
                    header(HttpHeaders.Authorization, "Bearer ${ctx.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `protected endpoint should accept requests with valid token via cookie`() {
        testApplication {
            val ctx = createTestContext()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/protected") {
                        val token = ctx.authRoutes.extractAccessToken(call)
                        if (token == null) {
                            call.respondWithUnauthorized()
                            return@get
                        }
                        val result = ctx.authService.authorize(token)
                        if (result is Either.Left) {
                            call.respondWithUnauthorized()
                            return@get
                        }
                        call.respondText("Success")
                    }
                }
            }

            val response =
                client.get("/api/protected") {
                    header(HttpHeaders.Cookie, "access_token=${ctx.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `protected endpoint should accept requests with valid token via query parameter`() {
        testApplication {
            val ctx = createTestContext()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/protected") {
                        val token = ctx.authRoutes.extractAccessToken(call)
                        if (token == null) {
                            call.respondWithUnauthorized()
                            return@get
                        }
                        val result = ctx.authService.authorize(token)
                        if (result is Either.Left) {
                            call.respondWithUnauthorized()
                            return@get
                        }
                        call.respondText("Success")
                    }
                }
            }

            val response = client.get("/api/protected?access_token=${ctx.token}")

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `protected POST endpoint should reject requests without authentication`() {
        testApplication {
            val ctx = createTestContext()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    post("/api/files/upload") {
                        val token = ctx.authRoutes.extractAccessToken(call)
                        if (token == null) {
                            call.respondWithUnauthorized()
                            return@post
                        }
                        val result = ctx.authService.authorize(token)
                        if (result is Either.Left) {
                            call.respondWithUnauthorized()
                            return@post
                        }
                        call.respondText("Uploaded")
                    }
                }
            }

            val response =
                client.post("/api/files/upload") {
                    header(HttpHeaders.ContentType, ContentType.MultiPart.FormData)
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `protected POST endpoint should accept requests with valid token`() {
        testApplication {
            val ctx = createTestContext()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    post("/api/submit") {
                        val token = ctx.authRoutes.extractAccessToken(call)
                        if (token == null) {
                            call.respondWithUnauthorized()
                            return@post
                        }
                        val result = ctx.authService.authorize(token)
                        if (result is Either.Left) {
                            call.respondWithUnauthorized()
                            return@post
                        }
                        call.respondText("Submitted")
                    }
                }
            }

            val response =
                client.post("/api/submit") {
                    header(HttpHeaders.Authorization, "Bearer ${ctx.token}")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"data":"test"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `session token should contain user info via protected route`() = testApplication {
        val ctx = createTestContext()

        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/api/protected") {
                    val token =
                        ctx.authRoutes.extractAccessToken(call)
                            ?: run {
                                call.respondWithUnauthorized()
                                return@get
                            }
                    val result = ctx.authService.authorize(token)
                    if (result is Either.Left) {
                        call.respondWithUnauthorized()
                        return@get
                    }
                    val session = result.getOrNull()!!
                    call.respondText(session.user.username)
                }
            }
        }

        val response =
            client.get("/api/protected") {
                header(HttpHeaders.Authorization, "Bearer ${ctx.token}")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("secuser"))
    }

    @Test
    fun `different sessions should have different tokens`() {
        testApplication {
            val ctx = createTestContext()

            // Login a second time to get a different token
            val login2 = ctx.authService.login("secuser", "secpass").getOrNull()!!
            assertNotNull(login2)
            val token2 = login2.rawToken

            // Tokens should be different
            assertTrue(ctx.token != token2)

            // Both should be valid
            val authorize1 = ctx.authService.authorize(ctx.token)
            val authorize2 = ctx.authService.authorize(token2)
            assertTrue(authorize1 is Either.Right)
            assertTrue(authorize2 is Either.Right)
        }
    }

    @Test
    fun `logout invalidates session token`() {
        testApplication {
            val ctx = createTestContext()

            // Logout the session
            val _ = ctx.authService.logout(ctx.token).getOrElse { error(it.errorMessage) }

            // Token should now be invalid
            val result = ctx.authService.authorize(ctx.token)
            assertTrue(result is Either.Left)
            assertEquals("Unauthorized", result.value.errorMessage)
        }
    }

    @Test
    fun `tampered token should be rejected`() {
        testApplication {
            val ctx = createTestContext()

            // Tamper with the token
            val tamperedToken = ctx.token.dropLast(1) + "X"

            val result = ctx.authService.authorize(tamperedToken)
            assertTrue(result is Either.Left)
            assertEquals("Unauthorized", result.value.errorMessage)
        }
    }

    @Test
    fun `expired session should be rejected`() {
        testApplication {
            val db = Database.connectUnmanaged(":memory:")
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)
            val authService = AuthService(userSessionsDb)

            val _ = usersDb.createUser("expireuser", "testpass").getOrElse { throw it }

            // Login with a session that already expired (back-date it)
            val loginResult = authService.login("expireuser", "testpass").getOrNull()!!

            // Manually expire the session in DB
            val _ =
                db.query("UPDATE user_sessions SET expires_at = ? WHERE token_hash = ?") {
                    setLong(1, java.time.Instant.now().minusSeconds(3600).toEpochMilli())
                    setString(2, UserSessionsDatabase.hashToken(loginResult.rawToken))
                    executeUpdate()
                }

            // Should be rejected
            val result = authService.authorize(loginResult.rawToken)
            assertTrue(result is Either.Left)
            assertEquals("Unauthorized", result.value.errorMessage)
        }
    }
}
