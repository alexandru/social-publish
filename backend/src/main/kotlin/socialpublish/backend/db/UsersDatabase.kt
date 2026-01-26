package socialpublish.backend.db

import arrow.core.Either
import arrow.core.raise.either
import at.favre.lib.crypto.bcrypt.BCrypt as FavreBCrypt
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Database access layer for users and user sessions.
 *
 * Handles user authentication data and JWT session management. Note: Future support for refresh
 * tokens is planned.
 */
class UsersDatabase(private val db: Database) {

    /**
     * Create a new user with the given username and password.
     *
     * @param username Unique username for the user
     * @param password Plain text password that will be hashed with BCrypt
     * @return Either a DBException or the created User
     */
    suspend fun createUser(username: String, password: String): Either<DBException, User> = either {
        db.transaction {
            // Check if user already exists
            val existing =
                query("SELECT id FROM users WHERE username = ?") {
                    setString(1, username)
                    executeQuery().safe().firstOrNull { rs -> rs.getString("id") }
                }

            if (existing != null) {
                raise(
                    DBException(
                        message = "User with username '$username' already exists",
                        cause = null,
                    )
                )
            }

            val id = UUID.randomUUID().toString()
            val passwordHash = hashPassword(password)
            val now = db.clock.instant()

            query(
                "INSERT INTO users (id, username, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"
            ) {
                setString(1, id)
                setString(2, username)
                setString(3, passwordHash)
                setLong(4, now.toEpochMilli())
                setLong(5, now.toEpochMilli())
                execute()
                Unit
            }

            logger.info { "Created user: $username (id: $id)" }

            User(
                id = id,
                username = username,
                passwordHash = passwordHash,
                createdAt = now,
                updatedAt = now,
            )
        }
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
                        id = rs.getString("id"),
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
     * @param userId User ID for the session
     * @param tokenHash Hash of the JWT token (for revocation support)
     * @param expiresAt When the session expires
     * @param refreshTokenHash Optional hash of refresh token (for future refresh token support)
     * @return Either a DBException or the created UserSession
     */
    suspend fun createSession(
        userId: String,
        tokenHash: String,
        expiresAt: Instant,
        refreshTokenHash: String? = null,
    ): Either<DBException, UserSession> = either {
        db.transaction {
            val id = UUID.randomUUID().toString()
            val now = db.clock.instant()

            query(
                """
                INSERT INTO user_sessions 
                (id, user_id, token_hash, refresh_token_hash, expires_at, created_at) 
                VALUES (?, ?, ?, ?, ?, ?)
                """
                    .trimIndent()
            ) {
                setString(1, id)
                setString(2, userId)
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

            logger.info { "Created session for user: $userId (session id: $id)" }

            UserSession(
                id = id,
                userId = userId,
                tokenHash = tokenHash,
                refreshTokenHash = refreshTokenHash,
                expiresAt = expiresAt,
                createdAt = now,
            )
        }
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
                            id = rs.getString("id"),
                            userId = rs.getString("user_id"),
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
     * @return Either a DBException or the number of sessions deleted
     */
    suspend fun deleteSession(tokenHash: String): Either<DBException, Int> = either {
        db.transaction {
            query("DELETE FROM user_sessions WHERE token_hash = ?") {
                setString(1, tokenHash)
                executeUpdate()
            }
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

    companion object {
        /**
         * Hash a password using BCrypt with 12 rounds.
         *
         * @param password Plain text password to hash
         * @return BCrypt hash string
         */
        fun hashPassword(password: String, rounds: Int = 12): String {
            return String(
                FavreBCrypt.withDefaults().hash(rounds, password.toCharArray()),
                Charsets.UTF_8,
            )
        }
    }
}
