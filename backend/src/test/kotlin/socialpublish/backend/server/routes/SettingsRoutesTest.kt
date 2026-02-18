package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.db.Database
import socialpublish.backend.db.UserSettings
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.modules.AuthModule
import socialpublish.backend.server.ServerAuthConfig

class SettingsRoutesTest {
    private val config = ServerAuthConfig(jwtSecret = "test-secret-settings")
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun setupDb(): Pair<UsersDatabase, UUID> {
        val db = Database.connectUnmanaged(":memory:")
        val usersDb = UsersDatabase(db)
        val result = usersDb.createUser("settingsuser", "settingspass")
        val user =
            (result.getOrElse { throw it } as socialpublish.backend.db.CreateResult.Created).value
        return Pair(usersDb, user.uuid)
    }

    private fun authToken(userUuid: UUID): String {
        val authModule = AuthModule(config.jwtSecret)
        return authModule.generateToken("settingsuser", userUuid)
    }

    @Test
    fun `GET settings returns ConfiguredServices when none are stored`() {
        testApplication {
            val (usersDb, userUuid) = setupDb()
            val authRoutes = AuthRoutes(config, usersDb, null)
            val settingsRoutes = SettingsRoutes(usersDb)

            application {
                install(ContentNegotiation) { json() }
                authRoutes.configureAuth(this)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/account/settings") {
                            settingsRoutes.getSettingsRoute(userUuid, call)
                        }
                    }
                }
            }

            val response =
                client.get("/api/account/settings") {
                    header(HttpHeaders.Authorization, "Bearer ${authToken(userUuid)}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val result = json.decodeFromString<ConfiguredServices>(response.bodyAsText())
            assertEquals(false, result.bluesky)
            assertEquals(false, result.mastodon)
            assertEquals(false, result.twitter)
            assertEquals(false, result.linkedin)
            assertEquals(false, result.llm)
        }
    }

    @Test
    fun `PUT settings persists and GET reflects configuration`() {
        testApplication {
            val (usersDb, userUuid) = setupDb()
            val authRoutes = AuthRoutes(config, usersDb, null)
            val settingsRoutes = SettingsRoutes(usersDb)

            application {
                install(ContentNegotiation) { json() }
                authRoutes.configureAuth(this)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/account/settings") {
                            settingsRoutes.getSettingsRoute(userUuid, call)
                        }
                        put("/api/account/settings") {
                            settingsRoutes.updateSettingsRoute(userUuid, call)
                        }
                    }
                }
            }

            val newSettings =
                UserSettings(
                    mastodon =
                        MastodonConfig(host = "https://mastodon.social", accessToken = "abc123")
                )

            val putResponse =
                client.put("/api/account/settings") {
                    header(HttpHeaders.Authorization, "Bearer ${authToken(userUuid)}")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(Json.encodeToString(UserSettings.serializer(), newSettings))
                }

            assertEquals(HttpStatusCode.OK, putResponse.status)

            val getResponse =
                client.get("/api/account/settings") {
                    header(HttpHeaders.Authorization, "Bearer ${authToken(userUuid)}")
                }

            assertEquals(HttpStatusCode.OK, getResponse.status)
            val configured = json.decodeFromString<ConfiguredServices>(getResponse.bodyAsText())
            assertEquals(true, configured.mastodon)
            assertEquals(false, configured.bluesky)
        }
    }

    @Test
    fun `GET settings returns 401 without auth token`() {
        testApplication {
            val (usersDb, _) = setupDb()
            val authRoutes = AuthRoutes(config, usersDb, null)
            val settingsRoutes = SettingsRoutes(usersDb)

            application {
                install(ContentNegotiation) { json() }
                authRoutes.configureAuth(this)
                routing {
                    authenticate("auth-jwt") {
                        get("/api/account/settings") {
                            settingsRoutes.getSettingsRoute(UUID.randomUUID(), call)
                        }
                    }
                }
            }

            val response = client.get("/api/account/settings")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `PUT settings returns 400 for invalid JSON`() {
        testApplication {
            val (usersDb, userUuid) = setupDb()
            val authRoutes = AuthRoutes(config, usersDb, null)
            val settingsRoutes = SettingsRoutes(usersDb)

            application {
                install(ContentNegotiation) { json() }
                authRoutes.configureAuth(this)
                routing {
                    authenticate("auth-jwt") {
                        put("/api/account/settings") {
                            settingsRoutes.updateSettingsRoute(userUuid, call)
                        }
                    }
                }
            }

            val response =
                client.put("/api/account/settings") {
                    header(HttpHeaders.Authorization, "Bearer ${authToken(userUuid)}")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("not valid json {{{")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid settings body"))
        }
    }
}
