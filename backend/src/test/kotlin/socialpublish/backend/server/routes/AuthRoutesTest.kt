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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import socialpublish.backend.db.Database
import socialpublish.backend.db.DocumentsDatabase
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.modules.AuthModule
import socialpublish.backend.server.ServerAuthConfig

@Serializable private data class TokenResponse(val token: String?)

class AuthRoutesTest {
    private val config = ServerAuthConfig(jwtSecret = "test-secret")

    private suspend fun testUsersDb(password: String = "testpass"): UsersDatabase {
        val db = Database.connectUnmanaged(":memory:")
        val usersDb = UsersDatabase(db)
        val _ = usersDb.createUser("testuser", password)
        return usersDb
    }

    private suspend fun emptyUsersDb(): UsersDatabase {
        val db = Database.connectUnmanaged(":memory:")
        return UsersDatabase(db)
    }

    @Test
    fun `login should work with correct password`() {
        testApplication {
            val usersDb = testUsersDb()
            val tempDb = Database.connectUnmanaged(":memory:")
            val authRoute = AuthRoutes(config, usersDb, DocumentsDatabase(tempDb))

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
            assertTrue(body.token.isNotBlank())
        }
    }

    @Test
    fun `login should reject wrong password`() {
        testApplication {
            val usersDb = testUsersDb()
            val tempDb = Database.connectUnmanaged(":memory:")
            val authRoute = AuthRoutes(config, usersDb, DocumentsDatabase(tempDb))

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
    fun `login should reject wrong username`() {
        testApplication {
            val usersDb = testUsersDb()
            val tempDb = Database.connectUnmanaged(":memory:")
            val authRoute = AuthRoutes(config, usersDb, DocumentsDatabase(tempDb))

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
    fun `login should return twitter auth status`() {
        testApplication {
            val usersDb = testUsersDb()
            val tempDb = Database.connectUnmanaged(":memory:")
            val authRoute = AuthRoutes(config, usersDb, DocumentsDatabase(tempDb))

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
            assertTrue(body.configuredServices.twitter)
        }
    }

    @Test
    fun `login should return linkedin auth status`() {
        testApplication {
            val usersDb = testUsersDb()
            val authRoute = AuthRoutes(config, usersDb, null)

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
            assertTrue(body.configuredServices.linkedin)
        }
    }

    @Test
    fun `login should work with form-urlencoded data`() {
        testApplication {
            val usersDb = testUsersDb()
            val tempDb = Database.connectUnmanaged(":memory:")
            val authRoute = AuthRoutes(config, usersDb, DocumentsDatabase(tempDb))

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
                val authRoutes = AuthRoutes(config, emptyUsersDb(), null)
                configureAuth(authRoutes)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") { authRoutes.protectedRoute(call) }
                    }
                }
            }

            val token = authModule.generateToken("testuser", UUID.randomUUID())
            val response = client.get("/api/protected?access_token=$token")

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `login should reject malformed JSON`() {
        testApplication {
            val authRoute = AuthRoutes(config, emptyUsersDb(), null)

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
            val authRoute = AuthRoutes(config, emptyUsersDb(), null)

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
            val authRoute = AuthRoutes(config, emptyUsersDb(), null)

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
            val authRoute = AuthRoutes(config, emptyUsersDb(), null)

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
                val authRoutes = AuthRoutes(config, emptyUsersDb(), null)
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
                val authRoutes = AuthRoutes(config, emptyUsersDb(), null)
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
                val authRoutes = AuthRoutes(config, emptyUsersDb(), null)
                configureAuth(authRoutes)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/protected") { authRoutes.protectedRoute(call) }
                    }
                }
            }

            val token = authModule.generateToken("testuser", UUID.randomUUID())
            val response =
                client.get("/api/protected") { header(HttpHeaders.Authorization, "Bearer $token") }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `extractJwtToken should extract token from Bearer header`() {
        testApplication {
            val authRoutes = AuthRoutes(config, emptyUsersDb(), null)
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
            val authRoutes = AuthRoutes(config, emptyUsersDb(), null)

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
            val authRoutes = AuthRoutes(config, emptyUsersDb(), null)

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
            val authRoutes = AuthRoutes(config, emptyUsersDb(), null)

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
            val authRoutes = AuthRoutes(config, emptyUsersDb(), null)

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
            val authRoutes = AuthRoutes(config, emptyUsersDb(), null)

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
            val usersDb = testUsersDb()
            val authRoute = AuthRoutes(config, usersDb, null)

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
            assertTrue(body.configuredServices.twitter)
            assertTrue(body.configuredServices.linkedin)
        }
    }

    @Test
    fun `login should return auth status with both providers false`() {
        testApplication {
            val usersDb = testUsersDb()
            val authRoute = AuthRoutes(config, usersDb, null)

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
            assertEquals(false, body.configuredServices.twitter)
            assertEquals(false, body.configuredServices.linkedin)
        }
    }
}
