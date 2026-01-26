package socialpublish.backend.db

import arrow.core.Either
import arrow.core.raise.either
import at.favre.lib.crypto.bcrypt.BCrypt as FavreBCrypt
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import socialpublish.backend.modules.AuthModule

private val logger = KotlinLogging.logger {}

/**
 * Database access layer for users and user sessions.
 *
 * Handles user authentication data and JWT session management. Note: Future support for refresh
 * tokens is planned.
 */
@OptIn(ExperimentalUuidApi::class)
class UsersDatabase(private val db: Database) {

    /**
     * Create a new user with the given username and password.
     *
     * @param username Unique username for the user
     * @param password Plain text password that will be hashed with BCrypt
     * @return Either a DBException or the CreateUserResult
     */
    suspend fun createUser(
        username: String,
        password: String,
    ): Either<DBException, CreateUserResult> = either {
        val result =
            db.transaction {
                // Check if user already exists
                val existing =
                    query("SELECT uuid FROM users WHERE username = ?") {
                        setString(1, username)
                        executeQuery().safe().firstOrNull { rs -> rs.getString("uuid") }
                    }

                if (existing != null) {
                    CreateUserResult.DuplicateUsername(username)
                } else {
                    val uuid = Uuid.generateV7()
                    val passwordHash = AuthModule.hashPassword(password)
                    val now = db.clock.instant()

                    query(
                        "INSERT INTO users (uuid, username, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"
                    ) {
                        setString(1, uuid.toString())
                        setString(2, username)
                        setString(3, passwordHash)
                        setLong(4, now.toEpochMilli())
                        setLong(5, now.toEpochMilli())
                        execute()
                        Unit
                    }

                    logger.info { "Created user: $username (uuid: $uuid)" }

                    val user =
                        User(
                            uuid = uuid,
                            username = username,
                            passwordHash = passwordHash,
                            createdAt = now,
                            updatedAt = now,
                        )
                    CreateUserResult.Created(user)
                }
            }
        result
    }

    /**
     * Find a user by username.
     *
     * @param username Username to search for
     * @return Either a DBException or the User (null if not found)
     */
    suspend fun findByUsername(username: String): Either<DBException, User?> = either {
        db.transaction {
            query("SELECT * FROM users WHERE username = ?") {
                setString(1, username)
                executeQuery().safe().firstOrNull { rs ->
                    User(
                        uuid = Uuid.parse(rs.getString("uuid")),
                        username = rs.getString("username"),
                        passwordHash = rs.getString("password_hash"),
                        createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                        updatedAt = Instant.ofEpochMilli(rs.getLong("updated_at")),
                    )
                }
            }
        }
    }

    /**
     * Verify a password against a user's stored hash.
     *
     * @param username Username of the user
     * @param password Plain text password to verify
     * @return Either a DBException or true if password matches, false otherwise
     */
    suspend fun verifyPassword(username: String, password: String): Either<DBException, Boolean> =
        either {
            val user = findByUsername(username).bind()
            if (user == null) {
                return@either false
            }

            val result =
                FavreBCrypt.verifyer()
                    .verify(password.toCharArray(), user.passwordHash.toCharArray())
            result.verified
        }

    /**
     * Create a new user session.
     *
     * @param userUuid User UUID for the session
     * @param tokenHash Hash of the JWT token (for revocation support)
     * @param expiresAt When the session expires
     * @param refreshTokenHash Optional hash of refresh token (for future refresh token support)
     * @return Either a DBException or the CreateSessionResult
     */
    suspend fun createSession(
        userUuid: Uuid,
        tokenHash: String,
        expiresAt: Instant,
        refreshTokenHash: String? = null,
    ): Either<DBException, CreateSessionResult> = either {
        val result =
            db.transaction {
                // Check if session with this token already exists
                val existing =
                    query("SELECT uuid FROM user_sessions WHERE token_hash = ?") {
                        setString(1, tokenHash)
                        executeQuery().safe().firstOrNull { rs -> rs.getString("uuid") }
                    }

                if (existing != null) {
                    CreateSessionResult.DuplicateToken
                } else {
                    val uuid = Uuid.generateV7()
                    val now = db.clock.instant()

                    query(
                        """
                    INSERT INTO user_sessions 
                    (uuid, user_uuid, token_hash, refresh_token_hash, expires_at, created_at) 
                    VALUES (?, ?, ?, ?, ?, ?)
                    """
                    ) {
                        setString(1, uuid.toString())
                        setString(2, userUuid.toString())
                        setString(3, tokenHash)
                        if (refreshTokenHash != null) {
                            setString(4, refreshTokenHash)
                        } else {
                            setNull(4, java.sql.Types.VARCHAR)
                        }
                        setLong(5, expiresAt.toEpochMilli())
                        setLong(6, now.toEpochMilli())
                        execute()
                        Unit
                    }

                    logger.info { "Created session for user: $userUuid (session uuid: $uuid)" }

                    val session =
                        UserSession(
                            uuid = uuid,
                            userUuid = userUuid,
                            tokenHash = tokenHash,
                            refreshTokenHash = refreshTokenHash,
                            expiresAt = expiresAt,
                            createdAt = now,
                        )
                    CreateSessionResult.Created(session)
                }
            }
        result
    }

    /**
     * Find a user session by token hash.
     *
     * @param tokenHash Hash of the JWT token
     * @return Either a DBException or the UserSession (null if not found)
     */
    suspend fun findSessionByTokenHash(tokenHash: String): Either<DBException, UserSession?> =
        either {
            db.transaction {
                query("SELECT * FROM user_sessions WHERE token_hash = ?") {
                    setString(1, tokenHash)
                    executeQuery().safe().firstOrNull { rs ->
                        UserSession(
                            uuid = Uuid.parse(rs.getString("uuid")),
                            userUuid = Uuid.parse(rs.getString("user_uuid")),
                            tokenHash = rs.getString("token_hash"),
                            refreshTokenHash = rs.getString("refresh_token_hash"),
                            expiresAt = Instant.ofEpochMilli(rs.getLong("expires_at")),
                            createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                        )
                    }
                }
            }
        }

    /**
     * Delete a user session (logout).
     *
     * @param tokenHash Hash of the JWT token
     * @return Either a DBException or true if session was deleted, false if not found
     */
    suspend fun deleteSession(tokenHash: String): Either<DBException, Boolean> = either {
        db.transaction {
            val deleted =
                query("DELETE FROM user_sessions WHERE token_hash = ?") {
                    setString(1, tokenHash)
                    executeUpdate()
                }
            deleted > 0
        }
    }

    /**
     * Delete all expired sessions.
     *
     * @return Either a DBException or the number of sessions deleted
     */
    suspend fun deleteExpiredSessions(): Either<DBException, Int> = either {
        db.transaction {
            val now = db.clock.instant()
            query("DELETE FROM user_sessions WHERE expires_at < ?") {
                setLong(1, now.toEpochMilli())
                executeUpdate()
            }
        }
    }
}
