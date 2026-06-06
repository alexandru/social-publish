@file:MustUseReturnValues

package socialpublish.backend.db

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.context.raise
import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.withContext

/** Database access layer for the user_sessions table. */
class UserSessionsDatabase(
    private val db: Database,
    private val usersDb: UsersDatabase,
) {
    /**
     * Called by the login endpoint, one time, for creating the new session
     * token.
     */
    suspend fun login(
        username: String,
        password: String,
    ): Either<DBException, NewUserSession?> =
        withContext(Database.Dispatcher) {
            either {
                val user =
                    usersDb.verifyPassword(username, password).bind()
                        ?: return@either null
                // Note this can block threads
                val rawToken = UUID.randomUUID().toString()
                val tokenHash = hashToken(rawToken)
                val expiresAt =
                    db.clock
                        .instant()
                        .plus(SESSION_DURATION_DAYS, ChronoUnit.DAYS)
                val sessionResult =
                    createSession(user.uuid, tokenHash, expiresAt).bind()
                when (sessionResult) {
                    CreateResult.Duplicate ->
                        raise(
                            DBException(
                                "Generated duplicate session token hash"
                            )
                        )
                    is CreateResult.Created ->
                        NewUserSession(rawToken, sessionResult.value)
                }
            }
        }

    suspend fun logout(token: String): Either<DBException, Boolean> =
        deleteSession(hashToken(token))

    suspend fun authorize(token: String): Either<DBException, UserSession?> =
        either {
            val tokenHash = hashToken(token)
            val session =
                findSessionByTokenHash(tokenHash).bind() ?: return@either null
            val now = db.clock.instant()
            if (session.revokedAt != null || !session.expiresAt.isAfter(now)) {
                val _ = deleteSession(tokenHash).bind()
                null
            } else {
                session
            }
        }

    suspend fun createSession(
        userUuid: UUIDv7,
        tokenHash: String,
        expiresAt: Instant,
    ): Either<DBException, CreateResult<UserSession>> = db.transaction {
        val user =
            usersDb.findByUuidInternal(userUuid)
                ?: raise(
                    DBException(
                        "Cannot create session for missing user: $userUuid"
                    )
                )

        val existing =
            query("SELECT uuid FROM user_sessions WHERE token_hash = ?") {
                setString(1, tokenHash)
                executeQuery().safe().firstOrNull { rs -> rs.getString("uuid") }
            }
        if (existing != null) return@transaction CreateResult.Duplicate

        val uuid = UUIDv7.generate()
        val now = db.clock.instant()

        query(
            """
                INSERT INTO user_sessions
                (uuid, user_uuid, token_hash, expires_at, created_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                """
        ) {
            setString(1, uuid.toString())
            setString(2, userUuid.toString())
            setString(3, tokenHash)
            setLong(4, expiresAt.toEpochMilli())
            setLong(5, now.toEpochMilli())
            execute()
            Unit
        }

        logger.info {
            "Created session for user: $userUuid (session uuid: $uuid)"
        }
        CreateResult.Created(
            UserSession(
                uuid = uuid,
                user = user,
                tokenHash = tokenHash,
                expiresAt = expiresAt,
                createdAt = now,
                revokedAt = null,
            )
        )
    }

    suspend fun findSessionByTokenHash(
        tokenHash: String
    ): Either<DBException, UserSession?> = db.transaction {
        query(
            """
                SELECT
                    s.uuid AS session_uuid,
                    s.token_hash,
                    s.expires_at AS session_expires_at,
                    s.created_at AS session_created_at,
                    s.revoked_at,
                    u.uuid AS user_uuid,
                    u.username AS user_username,
                    u.password_hash AS user_password_hash,
                    u.settings AS user_settings,
                    u.created_at AS user_created_at,
                    u.updated_at AS user_updated_at
                FROM user_sessions s
                JOIN users u ON u.uuid = s.user_uuid
                WHERE s.token_hash = ?
                """
        ) {
            setString(1, tokenHash)
            executeQuery().safe().firstOrNull { rs ->
                val revokedAtMillis = rs.getLong("revoked_at")
                val revokedAt =
                    if (rs.wasNull()) null
                    else Instant.ofEpochMilli(revokedAtMillis)
                UserSession(
                    uuid = UUIDv7.fromString(rs.getString("session_uuid")),
                    tokenHash = rs.getString("token_hash"),
                    expiresAt =
                        Instant.ofEpochMilli(rs.getLong("session_expires_at")),
                    createdAt =
                        Instant.ofEpochMilli(rs.getLong("session_created_at")),
                    revokedAt = revokedAt,
                    user =
                        User(
                            uuid = UUIDv7.fromString(rs.getString("user_uuid")),
                            username = rs.getString("user_username"),
                            passwordHash = rs.getString("user_password_hash"),
                            createdAt =
                                Instant.ofEpochMilli(
                                    rs.getLong("user_created_at")
                                ),
                            updatedAt =
                                Instant.ofEpochMilli(
                                    rs.getLong("user_updated_at")
                                ),
                            settings =
                                rs.getString("user_settings")?.let {
                                    UserSettings.parse(it).getOrElse {
                                        logger.error {
                                            "Invalid JSON for user settings"
                                        }
                                        null
                                    }
                                },
                        ),
                )
            }
        }
    }

    suspend fun deleteSession(tokenHash: String): Either<DBException, Boolean> =
        db.transaction {
            val deleted =
                query("DELETE FROM user_sessions WHERE token_hash = ?") {
                    setString(1, tokenHash)
                    executeUpdate()
                }
            deleted > 0
        }

    suspend fun deleteExpiredSessions(): Either<DBException, Int> =
        db.transaction {
            val now = db.clock.instant()
            query("DELETE FROM user_sessions WHERE expires_at < ?") {
                setLong(1, now.toEpochMilli())
                executeUpdate()
            }
        }

    companion object {
        private const val SESSION_DURATION_DAYS = 90L

        fun hashToken(token: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

private val logger = KotlinLogging.logger {}
