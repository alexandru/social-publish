package socialpublish.backend.db

import arrow.core.Either
import arrow.core.raise.either
import java.time.Instant
import java.util.UUID

/** User record in the database */
data class UserRow(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** New user data for creation */
data class NewUser(val email: String, val passwordHash: String)

/** User update data */
data class UserUpdate(val email: String? = null, val passwordHash: String? = null)

class UsersDatabase(private val db: Database) {
    suspend fun findById(id: UUID): Either<DBException, UserRow?> = either {
        db.query(
            "SELECT id, email, password_hash, created_at, updated_at FROM users WHERE id = ?"
        ) {
            setObject(1, id)
            executeQuery().safe().firstOrNull { rs ->
                UserRow(
                    id = UUID.fromString(rs.getString("id")),
                    email = rs.getString("email"),
                    passwordHash = rs.getString("password_hash"),
                    createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                    updatedAt = Instant.ofEpochMilli(rs.getLong("updated_at")),
                )
            }
        }
    }

    suspend fun findByEmail(email: String): Either<DBException, UserRow?> = either {
        db.query(
            "SELECT id, email, password_hash, created_at, updated_at FROM users WHERE email = ?"
        ) {
            setString(1, email)
            executeQuery().safe().firstOrNull { rs ->
                UserRow(
                    id = UUID.fromString(rs.getString("id")),
                    email = rs.getString("email"),
                    passwordHash = rs.getString("password_hash"),
                    createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                    updatedAt = Instant.ofEpochMilli(rs.getLong("updated_at")),
                )
            }
        }
    }

    suspend fun create(user: NewUser): Either<SqlUpdateException, UserRow> =
        db.transactionForUpdates {
            val id = UUID.randomUUID()
            val now = db.clock.instant()

            query(
                """
                INSERT INTO users (id, email, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """
                    .trimIndent()
            ) {
                setString(1, id.toString())
                setString(2, user.email)
                setString(3, user.passwordHash)
                setLong(4, now.toEpochMilli())
                setLong(5, now.toEpochMilli())
                execute()
                Unit
            }

            UserRow(
                id = id,
                email = user.email,
                passwordHash = user.passwordHash,
                createdAt = now,
                updatedAt = now,
            )
        }

    suspend fun update(id: UUID, update: UserUpdate): Either<DBException, Boolean> = either {
        db.transaction {
            val user = findById(id).bind() ?: return@transaction false
            val now = db.clock.instant()

            val updated =
                query(
                    """
                    UPDATE users 
                    SET email = ?, password_hash = ?, updated_at = ?
                    WHERE id = ?
                    """
                        .trimIndent()
                ) {
                    setString(1, update.email ?: user.email)
                    setString(2, update.passwordHash ?: user.passwordHash)
                    setLong(3, now.toEpochMilli())
                    setString(4, id.toString())
                    executeUpdate()
                }
            updated > 0
        }
    }

    suspend fun delete(id: UUID): Either<DBException, Boolean> = either {
        db.transaction {
            val wasDeleted =
                query("DELETE FROM users WHERE id = ?") {
                    setString(1, id.toString())
                    executeUpdate()
                }
            wasDeleted > 0
        }
    }

    suspend fun listAll(): Either<DBException, List<UserRow>> = either {
        db.query(
            "SELECT id, email, password_hash, created_at, updated_at FROM users ORDER BY created_at DESC"
        ) {
            executeQuery().safe().toList { rs ->
                UserRow(
                    id = UUID.fromString(rs.getString("id")),
                    email = rs.getString("email"),
                    passwordHash = rs.getString("password_hash"),
                    createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                    updatedAt = Instant.ofEpochMilli(rs.getLong("updated_at")),
                )
            }
        }
    }
}
