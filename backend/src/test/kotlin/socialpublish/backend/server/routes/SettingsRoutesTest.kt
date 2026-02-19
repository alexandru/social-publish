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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import socialpublish.backend.clients.bluesky.BlueskyConfig
import socialpublish.backend.clients.llm.LlmConfig
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.clients.metathreads.MetaThreadsConfig
import socialpublish.backend.common.Patched
import socialpublish.backend.db.CreateResult
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
        val user = (result.getOrElse { throw it } as CreateResult.Created).value
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
            assertNull(result.metaThreads)
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
        val patch = UserSettingsPatch(mastodon = Patched.Some(null))
        val result = mergeSettingsPatch(existing, patch)
        assertNull(result.mastodon)
    }

    @Test
    fun `Patched Some with absent sensitive field keeps existing credential`() {
        val patch =
            UserSettingsPatch(
                mastodon =
                    Patched.Some(
                        MastodonSettingsPatch(
                            host = Patched.Some("https://new.host")
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
                    Patched.Some(
                        MastodonSettingsPatch(
                            host = Patched.Some("https://new.host"),
                            accessToken = Patched.Some("new-token"),
                        )
                    )
            )
        val result = mergeSettingsPatch(existing, patch)
        assertEquals("new-token", result.mastodon?.accessToken)
    }

    @Test
    fun `toView masks all sensitive values`() {
        val settings =
            UserSettings(
                bluesky =
                    BlueskyConfig(
                        service = "https://bsky.social",
                        username = "alice.bsky.social",
                        password = "real-password",
                    ),
                mastodon = MastodonConfig(host = "https://mastodon.social", accessToken = "real"),
                metaThreads = MetaThreadsConfig(userId = "123", accessToken = "threads-secret"),
                llm =
                    LlmConfig(
                        apiUrl = "https://llm.example.com",
                        apiKey = "secret-key",
                        model = "gpt-4o-mini",
                    ),
            )

        val view = settings.toView()

        assertEquals(MASKED_VALUE, view.bluesky?.password)
        assertEquals(MASKED_VALUE, view.mastodon?.accessToken)
        assertEquals(MASKED_VALUE, view.metaThreads?.accessToken)
        assertEquals(MASKED_VALUE, view.llm?.apiKey)
    }

    @Test
    fun `toView returns empty sections for null settings`() {
        val view = (null as UserSettings?).toView()

        assertNull(view.bluesky)
        assertNull(view.mastodon)
        assertNull(view.twitter)
        assertNull(view.linkedin)
        assertNull(view.metaThreads)
        assertNull(view.llm)
    }

    @Test
    fun `Patched Some bluesky with blank service defaults to official host`() {
        val patch =
            UserSettingsPatch(
                bluesky =
                    Patched.Some(
                        BlueskySettingsPatch(
                            service = Patched.Some(""),
                            username = Patched.Some("alice.bsky.social"),
                            password = Patched.Some("app-password"),
                        )
                    )
            )

        val result = mergeSettingsPatch(existing = null, patch = patch)
        assertEquals("https://bsky.social", result.bluesky?.service)
    }

    @Test
    fun `Patched Some llm with undefined model defaults to empty string`() {
        val patch =
            UserSettingsPatch(
                llm =
                    Patched.Some(
                        LlmSettingsPatch(
                            apiUrl = Patched.Some("https://llm.example.com"),
                            apiKey = Patched.Some("secret"),
                        )
                    )
            )

        val result = mergeSettingsPatch(existing = null, patch = patch)
        assertEquals("", result.llm?.model)
    }

    @Test
    fun `Patched Some bluesky with blank required fields removes section`() {
        val existingWithBluesky =
            UserSettings(
                bluesky =
                    BlueskyConfig(
                        service = "https://bsky.social",
                        username = "alice.bsky.social",
                        password = "real-password",
                    )
            )
        val patch =
            UserSettingsPatch(
                bluesky =
                    Patched.Some(
                        BlueskySettingsPatch(
                            username = Patched.Some("   "),
                            password = Patched.Some("   "),
                        )
                    )
            )

        val result = mergeSettingsPatch(existingWithBluesky, patch)
        assertTrue(result.bluesky == null)
    }
}
