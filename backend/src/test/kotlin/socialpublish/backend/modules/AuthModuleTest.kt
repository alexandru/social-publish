package socialpublish.backend.modules

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
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import socialpublish.backend.server.ServerAuthConfig
import socialpublish.backend.server.routes.AuthRoutes
import socialpublish.backend.server.routes.LoginResponse
import socialpublish.backend.server.routes.configureAuth

@Serializable data class TokenResponse(val token: String?)

class AuthModuleTest {
    // Use a BCrypt hash for "testpass" for all tests
    private val testPasswordHash = AuthModule.hashPassword("testpass", rounds = 10)
    private val config =
        ServerAuthConfig(
            username = "testuser",
            passwordHash = testPasswordHash,
            jwtSecret = "test-secret",
        )

    @Test
    fun `should generate valid JWT token`() {
        val authModule = AuthModule(config.jwtSecret)
        val token = authModule.generateToken("testuser")

        assertNotNull(token)
        assertTrue(token.isNotEmpty())
    }

    @Test
    fun `should verify valid JWT token`() {
        val authModule = AuthModule(config.jwtSecret)
        val token = authModule.generateToken("testuser")
        val username = authModule.verifyToken(token)

        assertEquals("testuser", username)
    }

    @Test
    fun `should reject invalid JWT token`() {
        val authModule = AuthModule(config.jwtSecret)
        val username = authModule.verifyToken("invalid-token")

        assertEquals(null, username)
    }

    @Test
    fun `login should work with correct password`() {
        testApplication {
            val authRoute = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `login should work with BCrypt hashed password`() {
        // Generate a fresh BCrypt hash for this test
        val bcryptHash = AuthModule.hashPassword("testpass", rounds = 10)
        val testConfig =
            ServerAuthConfig(
                username = "testuser",
                passwordHash = bcryptHash,
                jwtSecret = "test-secret",
            )

        testApplication {
            val authRoute = AuthRoutes(testConfig)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `login should reject wrong password with BCrypt`() {
        // Generate a fresh BCrypt hash for this test
        val bcryptHash = AuthModule.hashPassword("testpass", rounds = 10)
        val testConfig =
            ServerAuthConfig(
                username = "testuser",
                passwordHash = bcryptHash,
                jwtSecret = "test-secret",
            )

        testApplication {
            val authRoute = AuthRoutes(testConfig)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"testuser","password":"wrongpass"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `login should return twitter auth status`() {
        testApplication {
            val authRoute = AuthRoutes(config, twitterAuthProvider = { true })

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = Json { ignoreUnknownKeys = true }
            val body = json.decodeFromString(LoginResponse.serializer(), response.bodyAsText())
            assertTrue(body.hasAuth.twitter)
        }
    }

    @Test
    fun `login should return linkedin auth status`() {
        testApplication {
            val authRoute = AuthRoutes(config, linkedInAuthProvider = { true })

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = Json { ignoreUnknownKeys = true }
            val body = json.decodeFromString(LoginResponse.serializer(), response.bodyAsText())
            assertTrue(body.hasAuth.linkedin)
        }
    }

    @Test
    fun `login should work with form-urlencoded data`() {
        testApplication {
            val authRoute = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("username=testuser&password=testpass")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `should accept access token query param`() {
        testApplication {
            val authModule = AuthModule(config.jwtSecret)

            application {
                install(ContentNegotiation) { json() }
                configureAuth(config)
                val authRoutes = AuthRoutes(config)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") { authRoutes.protectedRoute(call) }
                    }
                }
            }

            val token = authModule.generateToken("testuser")
            val response = client.get("/api/protected?access_token=$token")

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `hashPassword should generate valid BCrypt hash`() {
        val password = "mySecurePassword123"
        val hash = AuthModule.hashPassword(password)

        // Verify the hash can be used to authenticate
        val testConfig =
            ServerAuthConfig(username = "user", passwordHash = hash, jwtSecret = "secret")

        testApplication {
            val authRoute = AuthRoutes(testConfig)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"user","password":"mySecurePassword123"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `verifyPassword should handle trimmed stored passwords`() {
        val authModule = AuthModule(config.jwtSecret)
        val password = "myPassword"
        val hash = AuthModule.hashPassword(password)

        // Test with whitespace around the hash
        val verified = authModule.verifyPassword(password, "  $hash  ")
        assertTrue(verified)
    }

    @Test
    fun `verifyPassword should return false for incorrect password`() {
        val authModule = AuthModule(config.jwtSecret)
        val hash = AuthModule.hashPassword("correctPassword")

        val verified = authModule.verifyPassword("wrongPassword", hash)
        assertEquals(false, verified)
    }

    @Test
    fun `verifyPassword should return false for malformed hash`() {
        val authModule = AuthModule(config.jwtSecret)

        val verified = authModule.verifyPassword("password", "not-a-valid-bcrypt-hash")
        assertEquals(false, verified)
    }

    @Test
    fun `verifyPassword should return false for empty hash`() {
        val authModule = AuthModule(config.jwtSecret)

        val verified = authModule.verifyPassword("password", "")
        assertEquals(false, verified)
    }

    @Test
    fun `verifyToken should reject token signed with different secret`() {
        val authModule1 = AuthModule("secret1")
        val authModule2 = AuthModule("secret2")

        val token = authModule1.generateToken("testuser")
        val username = authModule2.verifyToken(token)

        assertEquals(null, username)
    }

    @Test
    fun `verifyToken should reject malformed token`() {
        val authModule = AuthModule(config.jwtSecret)
        val username = authModule.verifyToken("not.a.valid.jwt.token")

        assertEquals(null, username)
    }

    @Test
    fun `verifyToken should reject empty token`() {
        val authModule = AuthModule(config.jwtSecret)
        val username = authModule.verifyToken("")

        assertEquals(null, username)
    }

    @Test
    fun `hashPassword with different rounds should produce different hashes`() {
        val password = "testPassword"
        val hash1 = AuthModule.hashPassword(password, rounds = 4)
        val hash2 = AuthModule.hashPassword(password, rounds = 8)

        // Both should be valid but different
        assertNotNull(hash1)
        assertNotNull(hash2)
        assertTrue(hash1 != hash2)

        // Both should verify the same password
        val authModule = AuthModule(config.jwtSecret)
        assertTrue(authModule.verifyPassword(password, hash1))
        assertTrue(authModule.verifyPassword(password, hash2))
    }

    @Test
    fun `login should reject wrong username`() {
        testApplication {
            val authRoute = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"wronguser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `login should reject malformed JSON`() {
        testApplication {
            val authRoute = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"testuser"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `login should reject unsupported content type`() {
        testApplication {
            val authRoute = AuthRoutes(config)

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
            val authRoute = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("password=testpass")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `login should reject form-urlencoded with missing password`() {
        testApplication {
            val authRoute = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("username=testuser")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `protectedRoute should reject request without authentication`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureAuth(config)
                val authRoutes = AuthRoutes(config)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") { authRoutes.protectedRoute(call) }
                    }
                }
            }

            val response = client.get("/api/protected")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `protectedRoute should reject invalid token`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureAuth(config)
                val authRoutes = AuthRoutes(config)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") { authRoutes.protectedRoute(call) }
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
    fun `protectedRoute should accept Bearer token`() {
        testApplication {
            val authModule = AuthModule(config.jwtSecret)

            application {
                install(ContentNegotiation) { json() }
                configureAuth(config)
                val authRoutes = AuthRoutes(config)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") { authRoutes.protectedRoute(call) }
                    }
                }
            }

            val token = authModule.generateToken("testuser")
            val response =
                client.get("/api/protected") { header(HttpHeaders.Authorization, "Bearer $token") }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `extractJwtToken should extract token from Bearer header`() {
        testApplication {
            val authModule = AuthModule(config.jwtSecret)
            val authRoutes = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/test") {
                        val token = authRoutes.extractJwtToken(call)
                        call.respond(TokenResponse(token = token))
                    }
                }
            }

            val response =
                client.get("/test") { header(HttpHeaders.Authorization, "Bearer test-token-123") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("test-token-123"))
        }
    }

    @Test
    fun `extractJwtToken should extract token from cookie`() {
        testApplication {
            val authRoutes = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/test") {
                        val token = authRoutes.extractJwtToken(call)
                        call.respond(TokenResponse(token = token))
                    }
                }
            }

            val response =
                client.get("/test") { header(HttpHeaders.Cookie, "access_token=cookie-token-456") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("cookie-token-456"))
        }
    }

    @Test
    fun `extractJwtToken should return null for malformed Authorization header`() {
        testApplication {
            val authRoutes = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/test") {
                        val token = authRoutes.extractJwtToken(call)
                        call.respond(TokenResponse(token = token))
                    }
                }
            }

            val response =
                client.get("/test") { header(HttpHeaders.Authorization, "InvalidFormat") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("null"))
        }
    }

    @Test
    fun `extractJwtToken should return null when no token present`() {
        testApplication {
            val authRoutes = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/test") {
                        val token = authRoutes.extractJwtToken(call)
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
    fun `extractJwtToken should prioritize Bearer header over query param`() {
        testApplication {
            val authRoutes = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/test") {
                        val token = authRoutes.extractJwtToken(call)
                        call.respond(TokenResponse(token = token))
                    }
                }
            }

            val response =
                client.get("/test?access_token=query-token") {
                    header(HttpHeaders.Authorization, "Bearer header-token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("header-token"))
        }
    }

    @Test
    fun `extractJwtToken should prioritize query param over cookie`() {
        testApplication {
            val authRoutes = AuthRoutes(config)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/test") {
                        val token = authRoutes.extractJwtToken(call)
                        call.respond(TokenResponse(token = token))
                    }
                }
            }

            val response =
                client.get("/test?access_token=query-token") {
                    header(HttpHeaders.Cookie, "access_token=cookie-token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("query-token"))
        }
    }

    @Test
    fun `login should return auth status with both providers true`() {
        testApplication {
            val authRoute =
                AuthRoutes(config, twitterAuthProvider = { true }, linkedInAuthProvider = { true })

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = Json { ignoreUnknownKeys = true }
            val body = json.decodeFromString(LoginResponse.serializer(), response.bodyAsText())
            assertTrue(body.hasAuth.twitter)
            assertTrue(body.hasAuth.linkedin)
        }
    }

    @Test
    fun `login should return auth status with both providers false`() {
        testApplication {
            val authRoute =
                AuthRoutes(
                    config,
                    twitterAuthProvider = { false },
                    linkedInAuthProvider = { false },
                )

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authRoute.loginRoute(call) } }
            }

            val response =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = Json { ignoreUnknownKeys = true }
            val body = json.decodeFromString(LoginResponse.serializer(), response.bodyAsText())
            assertEquals(false, body.hasAuth.twitter)
            assertEquals(false, body.hasAuth.linkedin)
        }
    }
}
