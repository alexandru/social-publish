package socialpublish.backend.server

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.db.UUIDv7
import socialpublish.backend.db.User
import socialpublish.backend.db.UserSession
import socialpublish.backend.db.UserSettings

class ServerUtilsTest {
    private fun session(settings: UserSettings? = null): UserSession {
        val now = Instant.parse("2024-01-01T00:00:00Z")
        return UserSession(
            uuid = UUIDv7.generate(),
            user =
                User(
                    uuid = UUIDv7.generate(),
                    username = "user",
                    passwordHash = null,
                    settings = settings,
                    createdAt = now,
                    updatedAt = now,
                ),
            tokenHash = "token-hash",
            expiresAt = now.plusSeconds(60),
            createdAt = now,
            revokedAt = null,
        )
    }

    @Test
    fun `userUuid returns session user uuid`() {
        val session = session()

        val resolved = context(session) { userUuid() }

        assertEquals(session.user.uuid, resolved)
    }

    @Test
    fun `userSettings returns session user settings`() {
        val expected = UserSettings(mastodon = MastodonConfig("https://example.social", "token"))
        val session = session(settings = expected)

        val resolved = context(session) { userSettings() }

        assertEquals(expected, resolved)
    }

    @Test
    fun `userSettings returns empty settings when session user has no settings`() {
        val resolved = context(session(settings = null)) { userSettings() }

        assertEquals(UserSettings(), resolved)
    }
}
