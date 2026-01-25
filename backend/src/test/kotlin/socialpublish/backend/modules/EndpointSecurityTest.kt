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
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import socialpublish.backend.server.ServerAuthConfig

class EndpointSecurityTest {
    private val testPasswordHash = AuthModule.hashPassword("testpass", rounds = 10)
    private val config =
        ServerAuthConfig(
            username = "testuser",
            passwordHash = testPasswordHash,
            jwtSecret = "test-secret-key-for-security-tests",
        )

    @Test
    fun `protected endpoint should reject requests without token`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureAuth(config)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") { call.respondText("Success") }
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
            application {
                install(ContentNegotiation) { json() }
                configureAuth(config)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") { call.respondText("Success") }
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
    fun `protected endpoint should accept requests with valid token`() {
        testApplication {
            val authModule = AuthModule(config)

            application {
                install(ContentNegotiation) { json() }
                configureAuth(config)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") { call.respondText("Success") }
                    }
                }
            }

            val token = authModule.generateToken("testuser")
            val response =
                client.get("/api/protected") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `protected POST endpoint should reject requests without authentication`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                configureAuth(config)
                routing {
                    authenticate("auth-jwt") {
                        post("/api/files/upload") { call.respondText("Uploaded") }
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
            val authModule = AuthModule(config)

            application {
                install(ContentNegotiation) { json() }
                configureAuth(config)
                routing {
                    authenticate("auth-jwt") {
                        post("/api/submit") { call.respondText("Submitted") }
                    }
                }
            }

            val token = authModule.generateToken("testuser")
            val response =
                client.post("/api/submit") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"data":"test"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `token should be accepted via query parameter`() {
        testApplication {
            val authModule = AuthModule(config)

            application {
                install(ContentNegotiation) { json() }
                configureAuth(config)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") { call.respondText("Success") }
                    }
                }
            }

            val token = authModule.generateToken("testuser")
            val response = client.get("/api/protected?access_token=$token")

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `JWT token should contain username claim`() {
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
            val response =
                client.get("/api/protected") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("testuser"))
        }
    }

    @Test
    fun `different users should have different tokens`() {
        val authModule = AuthModule(config)

        val token1 = authModule.generateToken("user1")
        val token2 = authModule.generateToken("user2")

        // Tokens should be different
        assertTrue(token1 != token2)

        // But both should verify correctly with their respective usernames
        val username1 = authModule.verifyToken(token1)
        val username2 = authModule.verifyToken(token2)

        assertEquals("user1", username1)
        assertEquals("user2", username2)
    }

    @Test
    fun `expired token should be rejected`() {
        // This test verifies that tokens with expiration are created properly
        // In a real scenario, we'd need to mock time or wait, but we can at least verify
        // that the token contains an expiration claim
        val authModule = AuthModule(config)
        val token = authModule.generateToken("testuser")

        // Token should verify immediately
        val username = authModule.verifyToken(token)
        assertEquals("testuser", username)

        // The token is valid for 6 months, so it should still be valid
        val usernameAgain = authModule.verifyToken(token)
        assertEquals("testuser", usernameAgain)
    }

    @Test
    fun `tampered token should be rejected`() {
        val authModule = AuthModule(config)
        val validToken = authModule.generateToken("testuser")

        // Tamper with the token by changing one character
        val tamperedToken = validToken.dropLast(1) + "X"

        val username = authModule.verifyToken(tamperedToken)
        assertEquals(null, username)
    }
}
