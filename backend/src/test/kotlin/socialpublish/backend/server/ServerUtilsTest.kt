package socialpublish.backend.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.db.CreateResult
import socialpublish.backend.db.Database
import socialpublish.backend.db.UserSettings
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.modules.AuthModule
import socialpublish.backend.server.routes.AuthRoutes
import socialpublish.backend.server.routes.configureAuth

class ServerUtilsTest {
    private val authConfig = ServerAuthConfig(jwtSecret = "test-secret-server-utils")

    private suspend fun testUsersDb(): UsersDatabase {
        val db = Database.connectUnmanaged(":memory:")
        return UsersDatabase(db)
    }

    private suspend fun createUser(usersDb: UsersDatabase, username: String): UUID {
        val createResult = usersDb.createUser(username, "pass-$username")
        val created = createResult.getOrNull() as? CreateResult.Created
        return created?.value?.uuid ?: error("Failed to create user: $username")
    }

    @Test
    fun `requireUserUuid returns cached attribute value`() {
        testApplication {
            val expected = UUID.randomUUID()

            application {
                routing {
                    get("/cached") {
                        call.attributes.put(UserUuidKey, expected)
                        val resolved = call.requireUserUuid() ?: return@get
                        call.respondText(resolved.toString())
                    }
                }
            }

            val response = client.get("/cached")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(expected.toString(), response.bodyAsText())
        }
    }

    @Test
    fun `requireUserUuid resolves from authenticated principal and caches it`() {
        testApplication {
            val usersDb = testUsersDb()
            val authRoutes = AuthRoutes(authConfig, usersDb, documentsDb = null)
            val userUuid = UUID.randomUUID()
            val token = AuthModule(authConfig.jwtSecret).generateToken("user", userUuid)

            application {
                install(ContentNegotiation) { json() }
                configureAuth(authRoutes)
                routing {
                    authenticate("auth-jwt") {
                        get("/resolved") {
                            val resolved = call.requireUserUuid() ?: return@get
                            val cached = call.attributes.getOrNull(UserUuidKey)
                            call.respondText("$resolved|$cached")
                        }
                    }
                }
            }

            val response =
                client.get("/resolved") { header(HttpHeaders.Authorization, "Bearer $token") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("$userUuid|$userUuid", response.bodyAsText())
        }
    }

    @Test
    fun `requireUserUuid responds with unauthorized when no principal exists`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/missing-principal") {
                        val resolved = call.requireUserUuid()
                        if (resolved == null) return@get
                        call.respondText("should-not-happen")
                    }
                }
            }

            val response = client.get("/missing-principal")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Unauthorized"))
        }
    }

    @Test
    fun `requireUserSettings returns cached settings from attributes`() {
        testApplication {
            val usersDb = testUsersDb()
            val userUuid = UUID.randomUUID()
            val expected = UserSettings(mastodon = MastodonConfig("https://cached", "token"))

            application {
                routing {
                    get("/settings-cached") {
                        call.attributes.put(UserSettingsKey, expected)
                        val settings = call.requireUserSettings(usersDb, userUuid)
                        call.respondText(settings.mastodon?.host ?: "none")
                    }
                }
            }

            val response = client.get("/settings-cached")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("https://cached", response.bodyAsText())
        }
    }

    @Test
    fun `requireUserSettings loads settings from database and caches them`() {
        testApplication {
            val usersDb = testUsersDb()
            val userUuid = createUser(usersDb, "db-user")
            val _ =
                usersDb.updateSettings(
                    userUuid,
                    UserSettings(mastodon = MastodonConfig("https://db-host", "db-token")),
                )

            application {
                routing {
                    get("/settings-db") {
                        val settings = call.requireUserSettings(usersDb, userUuid)
                        val cached = call.attributes.getOrNull(UserSettingsKey)
                        call.respondText("${settings.mastodon?.host}|${cached?.mastodon?.host}")
                    }
                }
            }

            val response = client.get("/settings-db")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("https://db-host|https://db-host", response.bodyAsText())
        }
    }

    @Test
    fun `requireUserSettings returns empty settings when user does not exist`() {
        testApplication {
            val usersDb = testUsersDb()

            application {
                routing {
                    get("/settings-missing") {
                        val settings = call.requireUserSettings(usersDb, UUID.randomUUID())
                        call.respondText((settings == UserSettings()).toString())
                    }
                }
            }

            val response = client.get("/settings-missing")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("true", response.bodyAsText())
        }
    }
}
