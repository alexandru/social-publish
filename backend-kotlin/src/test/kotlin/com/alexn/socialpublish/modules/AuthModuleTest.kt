package com.alexn.socialpublish.modules

import com.alexn.socialpublish.server.ServerAuthConfig
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
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthModuleTest {
    private val config =
        ServerAuthConfig(
            username = "testuser",
            password = "testpass",
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
    fun `login should return twitter auth status`() {
        testApplication {
            val authModule = AuthModule(config, twitterAuthProvider = { true })

            application {
                install(ContentNegotiation) {
                    json()
                }
                routing {
                    post("/api/login") {
                        authModule.login(call)
                    }
                }
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
                install(ContentNegotiation) {
                    json()
                }
                configureAuth(config)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") {
                            authModule.protectedRoute(call)
                        }
                    }
                }
            }

            val token = authModule.generateToken("testuser")
            val response = client.get("/api/protected?access_token=$token")

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }
}
