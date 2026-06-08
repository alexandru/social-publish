package socialpublish.backend.server.routes

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
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
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import socialpublish.backend.db.Database
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.UserSession
import socialpublish.backend.db.UserSessionsDatabase
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.db.query
import socialpublish.backend.modules.AuthService
import socialpublish.backend.server.putUserSession
import socialpublish.backend.server.respondWithUnauthorized

@Serializable private data class TokenResponse(val token: String?)

class AuthRoutesTest {
    private suspend fun testSetup(password: String = "testpass"): TestContext {
        val db = Database.connectUnmanaged(":memory:")
        val usersDb = UsersDatabase(db)
        val userSessionsDb = UserSessionsDatabase(db, usersDb)
        val authService = AuthService(userSessionsDb)
        val authRoute = AuthRoutes(authService, DocumentsDatabase(db))
        val _ = usersDb.createUser("testuser", password).getOrElse { throw it }
        return TestContext(db, usersDb, userSessionsDb, authService, authRoute)
    }

    private data class TestContext(
        val db: Database,
        val usersDb: UsersDatabase,
        val userSessionsDb: UserSessionsDatabase,
        val authService: AuthService,
        val authRoute: AuthRoutes,
    )

    private suspend fun loginAndGetToken(ctx: TestContext): String {
        val created =
            ctx.authService.login("testuser", "testpass").getOrNull()!!
        return created.rawToken
    }

    /** Replicates the `authorized` pattern from Server.kt for use in tests. */
    private suspend fun authorizedEndpoint(
        call: io.ktor.server.application.ApplicationCall,
        ctx: TestContext,
        block:
            suspend context(UserSession)
            () -> Unit,
    ) {
        val token = ctx.authRoute.extractAccessToken(call)
        if (token == null) {
            call.respondWithUnauthorized()
            return
        }
        when (val result = ctx.authService.authorize(token)) {
            is Either.Left -> call.respondWithUnauthorized()
            is Either.Right -> {
                call.putUserSession(result.value)
                context(result.value) { block() }
            }
        }
    }

    @Test
    fun `login should work with correct password`() {
        testApplication {
            val ctx = testSetup()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    post("/api/login") { ctx.authRoute.loginRoute(call) }
                }
            }

            val response =
                client.post("/api/login") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json,
                    )
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = Json { ignoreUnknownKeys = true }
            val body =
                json.decodeFromString(
                    LoginResponse.serializer(),
                    response.bodyAsText(),
                )
            assertTrue(body.token.isNotBlank())
        }
    }

    @Test
    fun `login should reject wrong password`() {
        testApplication {
            val ctx = testSetup()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    post("/api/login") { ctx.authRoute.loginRoute(call) }
                }
            }

            val response =
                client.post("/api/login") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json,
                    )
                    setBody(
                        """{"username":"testuser","password":"wrongpass"}"""
                    )
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `login should reject wrong username`() {
        testApplication {
            val ctx = testSetup()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    post("/api/login") { ctx.authRoute.loginRoute(call) }
                }
            }

            val response =
                client.post("/api/login") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json,
                    )
                    setBody(
                        """{"username":"wronguser","password":"testpass"}"""
                    )
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `login should reject users with disabled password`() {
        testApplication {
            val db = Database.connectUnmanaged(":memory:")
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)
            val authService = AuthService(userSessionsDb)
            val authRoute = AuthRoutes(authService, null)
            val _ =
                usersDb.createUser("testuser", "testpass").getOrElse {
                    throw it
                }
            val _ =
                either {
                        db.query(
                            "UPDATE users SET password_hash = NULL WHERE username = ?"
                        ) {
                            setString(1, "testuser")
                            executeUpdate()
                        }
                    }
                    .getOrElse { throw it }

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json,
                    )
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `login should return twitter auth status`() {
        testApplication {
            val ctx = testSetup()
            val authRoute =
                AuthRoutes(ctx.authService, DocumentsDatabase(ctx.db))

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json,
                    )
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = Json { ignoreUnknownKeys = true }
            val body =
                json.decodeFromString(
                    LoginResponse.serializer(),
                    response.bodyAsText(),
                )
            // User has no OAuth token stored → twitter should be false
            assertEquals(false, body.configuredServices.twitter)
        }
    }

    @Test
    fun `login should return linkedin auth status`() {
        testApplication {
            val ctx = testSetup()
            val authRoute = AuthRoutes(ctx.authService, null)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json,
                    )
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = Json { ignoreUnknownKeys = true }
            val body =
                json.decodeFromString(
                    LoginResponse.serializer(),
                    response.bodyAsText(),
                )
            // User has no OAuth token stored → linkedin should be false
            assertEquals(false, body.configuredServices.linkedin)
        }
    }

    @Test
    fun `login should work with form-urlencoded data`() {
        testApplication {
            val ctx = testSetup()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    post("/api/login") { ctx.authRoute.loginRoute(call) }
                }
            }

            val response =
                client.post("/api/login") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded,
                    )
                    setBody("username=testuser&password=testpass")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `login should reject malformed JSON`() {
        testApplication {
            val db = Database.connectUnmanaged(":memory:")
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)
            val authService = AuthService(userSessionsDb)
            val authRoute = AuthRoutes(authService, null)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json,
                    )
                    setBody("""{"username":"testuser"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `login should reject unsupported content type`() {
        testApplication {
            val db = Database.connectUnmanaged(":memory:")
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)
            val authService = AuthService(userSessionsDb)
            val authRoute = AuthRoutes(authService, null)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Text.Plain)
                    setBody("username=testuser&password=testpass")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `login should reject form-urlencoded with missing username`() {
        testApplication {
            val db = Database.connectUnmanaged(":memory:")
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)
            val authService = AuthService(userSessionsDb)
            val authRoute = AuthRoutes(authService, null)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded,
                    )
                    setBody("password=testpass")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `login should reject form-urlencoded with missing password`() {
        testApplication {
            val db = Database.connectUnmanaged(":memory:")
            val usersDb = UsersDatabase(db)
            val userSessionsDb = UserSessionsDatabase(db, usersDb)
            val authService = AuthService(userSessionsDb)
            val authRoute = AuthRoutes(authService, null)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.FormUrlEncoded,
                    )
                    setBody("username=testuser")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `protectedRoute should reject request without Bearer token`() {
        testApplication {
            val ctx = testSetup()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/protected") {
                        authorizedEndpoint(call, ctx) {
                            call.respondText("Success")
                        }
                    }
                }
            }

            val response = client.get("/api/protected")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `protectedRoute should reject invalid Bearer token`() {
        testApplication {
            val ctx = testSetup()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/protected") {
                        authorizedEndpoint(call, ctx) {
                            call.respondText("Success")
                        }
                    }
                }
            }

            val response =
                client.get("/api/protected") {
                    header(HttpHeaders.Authorization, "Bearer invalid-token")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `protectedRoute should accept valid Bearer token`() {
        testApplication {
            val ctx = testSetup()
            val token = loginAndGetToken(ctx)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/protected") {
                        authorizedEndpoint(call, ctx) {
                            call.respondText("Success")
                        }
                    }
                }
            }

            val response =
                client.get("/api/protected") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `protectedRoute should accept valid token via access_token cookie`() {
        testApplication {
            val ctx = testSetup()
            val token = loginAndGetToken(ctx)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/protected") {
                        authorizedEndpoint(call, ctx) {
                            call.respondText("Success")
                        }
                    }
                }
            }

            val response =
                client.get("/api/protected") {
                    header(HttpHeaders.Cookie, "access_token=$token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `protectedRoute should reject valid token via access_token query parameter`() {
        testApplication {
            val ctx = testSetup()
            val token = loginAndGetToken(ctx)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/protected") {
                        authorizedEndpoint(call, ctx) {
                            call.respondText("Success")
                        }
                    }
                }
            }

            val response = client.get("/api/protected?access_token=$token")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `protectedRoute should reject token for deleted user`() {
        testApplication {
            val ctx = testSetup()
            val token = loginAndGetToken(ctx)

            // Delete the user from the database
            val _ =
                either {
                        ctx.db.query("DELETE FROM users WHERE username = ?") {
                            setString(1, "testuser")
                            executeUpdate()
                        }
                    }
                    .getOrElse { throw it }

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/protected") {
                        authorizedEndpoint(call, ctx) {
                            call.respondText("Success")
                        }
                    }
                }
            }

            val response =
                client.get("/api/protected") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            // Session is orphaned (user deleted) → should still be invalid
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `extractAccessToken should extract token from Authorization Bearer header`() {
        testApplication {
            val ctx = testSetup()
            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/test") {
                        val token = ctx.authRoute.extractAccessToken(call)
                        call.respond(TokenResponse(token = token))
                    }
                }
            }

            val response =
                client.get("/test") {
                    header(HttpHeaders.Authorization, "Bearer test-token-123")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("test-token-123"))
        }
    }

    @Test
    fun `extractAccessToken should return null for malformed Authorization header`() {
        testApplication {
            val ctx = testSetup()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/test") {
                        val token = ctx.authRoute.extractAccessToken(call)
                        call.respond(TokenResponse(token = token))
                    }
                }
            }

            val response =
                client.get("/test") {
                    header(HttpHeaders.Authorization, "InvalidFormat")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("null"))
        }
    }

    @Test
    fun `extractAccessToken should return null when no token present`() {
        testApplication {
            val ctx = testSetup()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/test") {
                        val token = ctx.authRoute.extractAccessToken(call)
                        call.respond(TokenResponse(token = token))
                    }
                }
            }

            val response = client.get("/test")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("null"))
        }
    }

    @Test
    fun `extractAccessToken should extract token from access_token cookie`() {
        testApplication {
            val ctx = testSetup()
            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/test") {
                        val token = ctx.authRoute.extractAccessToken(call)
                        call.respond(TokenResponse(token = token))
                    }
                }
            }

            val response =
                client.get("/test") {
                    header(HttpHeaders.Cookie, "access_token=cookie-token-456")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("cookie-token-456"))
        }
    }

    @Test
    fun `extractAccessToken should not extract token from access_token query parameter`() {
        testApplication {
            val ctx = testSetup()
            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/test") {
                        val token = ctx.authRoute.extractAccessToken(call)
                        call.respond(TokenResponse(token = token))
                    }
                }
            }

            val response = client.get("/test?access_token=query-token-789")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"token\":null"))
        }
    }

    @Test
    fun `login should return auth status with both providers false when no oauth`() {
        testApplication {
            val ctx = testSetup()
            val authRoute = AuthRoutes(ctx.authService, null)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json,
                    )
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = Json { ignoreUnknownKeys = true }
            val body =
                json.decodeFromString(
                    LoginResponse.serializer(),
                    response.bodyAsText(),
                )
            assertEquals(false, body.configuredServices.twitter)
            assertEquals(false, body.configuredServices.linkedin)
        }
    }

    @Test
    fun `logout should succeed with valid token`() {
        testApplication {
            val ctx = testSetup()
            val token = loginAndGetToken(ctx)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    post("/api/logout") {
                        val bearerToken = ctx.authRoute.extractAccessToken(call)
                        if (bearerToken != null) {
                            ctx.authRoute.logoutRoute(bearerToken, call)
                        } else {
                            call.respond(mapOf("success" to true))
                        }
                    }
                }
            }

            val response =
                client.post("/api/logout") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
        }
    }
}
