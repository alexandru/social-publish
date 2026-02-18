package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `GET settings returns empty view when none are stored`() {
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
            val result = json.decodeFromString<AccountSettingsView>(response.bodyAsText())
            assertNull(result.bluesky)
            assertNull(result.mastodon)
            assertNull(result.twitter)
            assertNull(result.linkedin)
            assertNull(result.llm)
        }
    }

    @Test
    fun `PATCH settings persists and GET returns masked view`() {
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
                        patch("/api/account/settings") {
                            settingsRoutes.patchSettingsRoute(userUuid, call)
                        }
                    }
                }
            }

            // Use JSON Merge Patch – just provide the fields we want to set
            val patchBody =
                """{"mastodon":{"host":"https://mastodon.social","accessToken":"abc123"}}"""

            val patchResponse =
                client.patch("/api/account/settings") {
                    header(HttpHeaders.Authorization, "Bearer ${authToken(userUuid)}")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(patchBody)
                }

            assertEquals(HttpStatusCode.OK, patchResponse.status)

            val getResponse =
                client.get("/api/account/settings") {
                    header(HttpHeaders.Authorization, "Bearer ${authToken(userUuid)}")
                }

            assertEquals(HttpStatusCode.OK, getResponse.status)
            val view = json.decodeFromString<AccountSettingsView>(getResponse.bodyAsText())
            assertEquals("https://mastodon.social", view.mastodon?.host)
            // sensitive field is masked
            assertEquals(MASKED_VALUE, view.mastodon?.accessToken)
            assertNull(view.bluesky)
        }
    }

    @Test
    fun `PATCH absent sensitive field keeps existing value`() {
        testApplication {
            val (usersDb, userUuid) = setupDb()
            val settingsRoutes = SettingsRoutes(usersDb)

            // Seed existing settings with a real token
            val _ =
                usersDb.updateSettings(
                    userUuid,
                    UserSettings(
                        mastodon =
                            MastodonConfig(host = "https://old.host", accessToken = "real-token")
                    ),
                )

            application {
                install(ContentNegotiation) { json() }
                routing {
                    patch("/api/account/settings") {
                        settingsRoutes.patchSettingsRoute(userUuid, call)
                    }
                }
            }

            // PATCH with updated host only — accessToken key is absent → keep existing
            val patchBody = """{"mastodon":{"host":"https://new.host"}}"""
            client.patch("/api/account/settings") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(patchBody)
            }

            val stored = usersDb.findByUuid(userUuid).getOrElse { throw it }
            assertEquals("https://new.host", stored?.settings?.mastodon?.host)
            // token was not sent → preserved unchanged
            assertEquals("real-token", stored?.settings?.mastodon?.accessToken)
        }
    }

    @Test
    fun `PATCH null section removes that section`() {
        testApplication {
            val (usersDb, userUuid) = setupDb()
            val settingsRoutes = SettingsRoutes(usersDb)

            val _ =
                usersDb.updateSettings(
                    userUuid,
                    UserSettings(
                        mastodon =
                            MastodonConfig(host = "https://mastodon.social", accessToken = "tok")
                    ),
                )

            application {
                install(ContentNegotiation) { json() }
                routing {
                    patch("/api/account/settings") {
                        settingsRoutes.patchSettingsRoute(userUuid, call)
                    }
                }
            }

            // null section → remove it
            val patchBody = """{"mastodon":null}"""
            client.patch("/api/account/settings") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(patchBody)
            }

            val stored = usersDb.findByUuid(userUuid).getOrElse { throw it }
            assertNull(stored?.settings?.mastodon)
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
    fun `PATCH settings returns 400 for invalid JSON`() {
        testApplication {
            val (usersDb, userUuid) = setupDb()
            val authRoutes = AuthRoutes(config, usersDb, null)
            val settingsRoutes = SettingsRoutes(usersDb)

            application {
                install(ContentNegotiation) { json() }
                authRoutes.configureAuth(this)
                routing {
                    authenticate("auth-jwt") {
                        patch("/api/account/settings") {
                            settingsRoutes.patchSettingsRoute(userUuid, call)
                        }
                    }
                }
            }

            val response =
                client.patch("/api/account/settings") {
                    header(HttpHeaders.Authorization, "Bearer ${authToken(userUuid)}")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("not valid json {{{")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `PATCH settings rejects masked sentinel in sensitive fields`() {
        testApplication {
            val (usersDb, userUuid) = setupDb()
            val authRoutes = AuthRoutes(config, usersDb, null)
            val settingsRoutes = SettingsRoutes(usersDb)

            application {
                install(ContentNegotiation) { json() }
                authRoutes.configureAuth(this)
                routing {
                    authenticate("auth-jwt") {
                        patch("/api/account/settings") {
                            settingsRoutes.patchSettingsRoute(userUuid, call)
                        }
                    }
                }
            }

            val response =
                client.patch("/api/account/settings") {
                    header(HttpHeaders.Authorization, "Bearer ${authToken(userUuid)}")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"mastodon":{"accessToken":"****"}}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }
}

class MergeSettingsPatchTest {
    private val existing =
        UserSettings(
            mastodon = MastodonConfig(host = "https://mastodon.social", accessToken = "real-token")
        )

    @Test
    fun `Patched Undefined keeps existing section`() {
        val patch = UserSettingsPatch() // all Undefined by default
        val result = mergeSettingsPatch(existing, patch)
        assertEquals("https://mastodon.social", result.mastodon?.host)
        assertEquals("real-token", result.mastodon?.accessToken)
    }

    @Test
    fun `Patched Some null removes section`() {
        val patch = UserSettingsPatch(mastodon = socialpublish.backend.common.Patched.Some(null))
        val result = mergeSettingsPatch(existing, patch)
        assertNull(result.mastodon)
    }

    @Test
    fun `Patched Some with absent sensitive field keeps existing credential`() {
        val patch =
            UserSettingsPatch(
                mastodon =
                    socialpublish.backend.common.Patched.Some(
                        MastodonSettingsPatch(
                            host = socialpublish.backend.common.Patched.Some("https://new.host")
                            // accessToken absent → Patched.Undefined → keep existing
                        )
                    )
            )
        val result = mergeSettingsPatch(existing, patch)
        assertEquals("https://new.host", result.mastodon?.host)
        assertEquals("real-token", result.mastodon?.accessToken)
    }

    @Test
    fun `Patched Some with new credential updates it`() {
        val patch =
            UserSettingsPatch(
                mastodon =
                    socialpublish.backend.common.Patched.Some(
                        MastodonSettingsPatch(
                            host = socialpublish.backend.common.Patched.Some("https://new.host"),
                            accessToken = socialpublish.backend.common.Patched.Some("new-token"),
                        )
                    )
            )
        val result = mergeSettingsPatch(existing, patch)
        assertEquals("new-token", result.mastodon?.accessToken)
    }
}
