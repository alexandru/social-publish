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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import socialpublish.backend.server.ServerAuthConfig

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
        val authModule = AuthModule(config)
        val token = authModule.generateToken("testuser")

        assertNotNull(token)
        assertTrue(token.isNotEmpty())
    }

    @Test
    fun `should verify valid JWT token`() {
        val authModule = AuthModule(config)
        val token = authModule.generateToken("testuser")
        val username = authModule.verifyToken(token)

        assertEquals("testuser", username)
    }

    @Test
    fun `should reject invalid JWT token`() {
        val authModule = AuthModule(config)
        val username = authModule.verifyToken("invalid-token")

        assertEquals(null, username)
    }

    @Test
    fun `login should work with correct password`() {
        testApplication {
            val authModule = AuthModule(config)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authModule.login(call) } }
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
            val authModule = AuthModule(testConfig)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authModule.login(call) } }
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
            val authModule = AuthModule(testConfig)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authModule.login(call) } }
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
            val authModule = AuthModule(config, twitterAuthProvider = { true })

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authModule.login(call) } }
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
    fun `should accept access token query param`() {
        testApplication {
            val authModule = AuthModule(config)

            application {
                install(ContentNegotiation) { json() }
                configureAuth(config)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") { authModule.protectedRoute(call) }
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

        // Verify it's a valid BCrypt hash format
        assertTrue(hash.matches(Regex("^\\$2[ayb]\\$\\d{2}\\$.+")))

        // Verify the hash can be used to authenticate
        val testConfig =
            ServerAuthConfig(username = "user", passwordHash = hash, jwtSecret = "secret")

        testApplication {
            val authModule = AuthModule(testConfig)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authModule.login(call) } }
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
    fun `login should be rate limited after too many failed attempts`() {
        testApplication {
            val authModule = AuthModule(config)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authModule.login(call) } }
            }

            // Make 5 failed login attempts
            repeat(5) {
                val response =
                    client.post("/api/login") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json)
                        setBody("""{"username":"testuser","password":"wrongpass"}""")
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

            // The 6th attempt should be rate limited
            val rateLimitedResponse =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"testuser","password":"wrongpass"}""")
                }
            assertEquals(HttpStatusCode.TooManyRequests, rateLimitedResponse.status)
        }
    }

    @Test
    fun `successful login should reset rate limiter`() {
        testApplication {
            val authModule = AuthModule(config)

            application {
                install(ContentNegotiation) { json() }
                routing { post("/api/login") { authModule.login(call) } }
            }

            // Make a few failed login attempts
            repeat(3) {
                val response =
                    client.post("/api/login") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json)
                        setBody("""{"username":"testuser","password":"wrongpass"}""")
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

            // Successful login should reset the counter
            val successResponse =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"testuser","password":"testpass"}""")
                }
            assertEquals(HttpStatusCode.OK, successResponse.status)

            // We should be able to attempt again even though we had 3 failures
            val afterSuccessResponse =
                client.post("/api/login") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"username":"testuser","password":"wrongpass"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, afterSuccessResponse.status)
        }
    }
}
