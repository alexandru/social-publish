package socialpublish.backend.db

import arrow.core.Either
import arrow.core.raise.either
import at.favre.lib.crypto.bcrypt.BCrypt as FavreBCrypt
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.PreparedStatement
import java.time.Instant
import socialpublish.backend.common.jsonCommon
import socialpublish.backend.modules.AuthModule

private val logger = KotlinLogging.logger {}

internal fun parseUserSettings(raw: String?): UserSettings? =
    if (raw != null) {
        try {
            jsonCommon.decodeFromString<UserSettings>(raw)
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse user settings" }
            null
        }
    } else {
        null
    }

/**
 * Database access layer for users.
 *
 * Handles user authentication data.
 */
class UsersDatabase(private val db: Database) {
    /**
     * Create a new user with the given username and password.
     *
     * @param username Unique username for the user
     * @param password Plain text password that will be hashed with BCrypt
     * @return Either a DBException or the CreateResult<User>
     */
    suspend fun createUser(
        username: String,
        password: String,
    ): Either<DBException, CreateResult<User>> = either {
        db.transaction {
            // Check if user already exists
            val existing =
                query("SELECT uuid FROM users WHERE username = ?") {
                    setString(1, username)
                    executeQuery().safe().firstOrNull { rs -> rs.getString("uuid") }
                }

            if (existing != null) {
                CreateResult.Duplicate
            } else {
                val uuid = UUIDv7.generate()
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
                        settings = null,
                        createdAt = now,
                        updatedAt = now,
                    )
                CreateResult.Created(user)
            }
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
                executeQueryGetUserOrNull()
            }
        }
    }

    /**
     * Find a user by UUID.
     *
     * @param uuid UUID of the user
     * @return Either a DBException or the User (null if not found)
     */
    suspend fun findByUuid(uuid: UUIDv7): Either<DBException, User?> = either {
        db.transaction {
            query("SELECT * FROM users WHERE uuid = ?") {
                setString(1, uuid.toString())
                executeQueryGetUserOrNull()
            }
        }
    }

    private fun PreparedStatement.executeQueryGetUserOrNull(): User? =
        executeQuery().safe().firstOrNull { rs ->
            User(
                uuid = UUIDv7.fromString(rs.getString("uuid")),
                username = rs.getString("username"),
                passwordHash = rs.getString("password_hash"),
                settings = parseUserSettings(rs.getString("settings")),
                createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                updatedAt = Instant.ofEpochMilli(rs.getLong("updated_at")),
            )
        }

    /**
     * Update a user's settings.
     *
     * @param uuid UUID of the user
     * @param settings New settings to store
     * @return Either a DBException or true if updated, false if user not found
     */
    suspend fun updateSettings(uuid: UUIDv7, settings: UserSettings): Either<DBException, Boolean> =
        either {
            db.transaction {
                val now = db.clock.instant()
                val encodedSettings = jsonCommon.encodeToString(settings)
                val updated =
                    query("UPDATE users SET settings = ?, updated_at = ? WHERE uuid = ?") {
                        setString(1, encodedSettings)
                        setLong(2, now.toEpochMilli())
                        setString(3, uuid.toString())
                        executeUpdate()
                    }
                updated > 0
            }
        }

    /**
     * Verify a password against a user's stored hash.
     *
     * @param username Username of the user
     * @param password Plain text password to verify
     * @return Either a DBException, the verified user when credentials match, or null otherwise
     */
    suspend fun verifyPassword(username: String, password: String): Either<DBException, User?> =
        either {
            val user = findByUsername(username).bind() ?: return@either null

            val result =
                user.passwordHash?.let {
                    FavreBCrypt.verifyer().verify(password.toCharArray(), it.toCharArray())
                } ?: return@either null
            if (result.verified) user else null
        }

    /**
     * Update a user's password.
     *
     * @param username Username of the user
     * @param newPassword New plain text password (will be hashed)
     * @return Either a DBException or true if updated, false if user not found
     */
    suspend fun updatePassword(
        username: String,
        newPassword: String,
    ): Either<DBException, Boolean> = either {
        val now = db.clock.instant()
        val passwordHash = AuthModule.hashPassword(newPassword)
        db.transaction {
            val userUuid =
                query("SELECT uuid FROM users WHERE username = ?") {
                    setString(1, username)
                    executeQuery().safe().firstOrNull { rs -> rs.getString("uuid") }
                } ?: return@transaction false

            val updated =
                query("UPDATE users SET password_hash = ?, updated_at = ? WHERE username = ?") {
                    setString(1, passwordHash)
                    setLong(2, now.toEpochMilli())
                    setString(3, username)
                    executeUpdate()
                }
            if (updated > 0) {
                val _ = query("DELETE FROM user_sessions WHERE user_uuid = ?") {
                    setString(1, userUuid)
                    executeUpdate()
                }
            }
            updated > 0
        }
    }

    /**
     * Update a user's username.
     *
     * @param currentUsername Current username of the user
     * @param newUsername New username for the user
     * @return Either a DBException or UpdateUsernameResult indicating the outcome
     */
    suspend fun updateUsername(
        currentUsername: String,
        newUsername: String,
    ): Either<DBException, UpdateUsernameResult> = either {
        when (
            val result = db.transactionForUpdates {
                val existingUser =
                    query("SELECT uuid FROM users WHERE username = ?") {
                        setString(1, currentUsername)
                        executeQuery().safe().firstOrNull { rs -> rs.getString("uuid") }
                    }

                if (existingUser == null) {
                    UpdateUsernameResult.UserNotFound
                } else {
                    val conflictingUser =
                        query("SELECT uuid FROM users WHERE username = ?") {
                            setString(1, newUsername)
                            executeQuery().safe().firstOrNull { rs -> rs.getString("uuid") }
                        }

                    if (conflictingUser != null && conflictingUser != existingUser) {
                        UpdateUsernameResult.UsernameAlreadyExists
                    } else {
                        val now = db.clock.instant()
                        val updated =
                            query(
                                "UPDATE users SET username = ?, updated_at = ? WHERE username = ?"
                            ) {
                                setString(1, newUsername)
                                setLong(2, now.toEpochMilli())
                                setString(3, currentUsername)
                                executeUpdate()
                            }
                        if (updated > 0) {
                            UpdateUsernameResult.Success
                        } else {
                            UpdateUsernameResult.UserNotFound
                        }
                    }
                }
            }
        ) {
            is Either.Left ->
                when (val error = result.value) {
                    is SqlUpdateException.UniqueViolation ->
                        UpdateUsernameResult.UsernameAlreadyExists
                    else -> raise(DBException(error.message ?: "Failed to update username", error))
                }
            is Either.Right -> result.value
        }
    }
}
