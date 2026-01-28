package socialpublish.backend.server.routes

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
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import socialpublish.backend.modules.AuthModule
import socialpublish.backend.server.ServerAuthConfig

@Serializable data class TokenResponse(val token: String?)

class AuthRoutesTest {
    private val testPasswordHash = AuthModule.hashPassword("testpass", rounds = 10)
    private val config =
        ServerAuthConfig(
            username = "testuser",
            passwordHash = testPasswordHash,
            jwtSecret = "test-secret",
        )

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
                val authRoutes = AuthRoutes(config)
                configureAuth(authRoutes)
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
                val authRoutes = AuthRoutes(config)
                configureAuth(authRoutes)
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
                val authRoutes = AuthRoutes(config)
                configureAuth(authRoutes)
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
                val authRoutes = AuthRoutes(config)
                configureAuth(authRoutes)
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
