package socialpublish.backend.db

import arrow.core.Either
import arrow.core.raise.either
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class UploadPayload(
    val hash: String,
    val originalname: String,
    val mimetype: String,
    val size: Long,
    val altText: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
)

data class Upload(
    val uuid: String,
    val hash: String,
    val originalname: String,
    val mimetype: String,
    val size: Long,
    val altText: String?,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val createdAt: Instant,
)

class FilesDatabase(private val db: Database) {
    companion object {
        private val UUID_NAMESPACE = UUID.fromString("5b9ba0d0-8825-4c51-a34e-f849613dbcac")

        /** Generate UUID v5 from string (deterministic UUID based on namespace and name) */
        fun generateUuidV5(name: String, namespace: UUID = UUID_NAMESPACE): UUID {
            val namespaceBytes =
                ByteBuffer.allocate(16)
                    .apply {
                        putLong(namespace.mostSignificantBits)
                        putLong(namespace.leastSignificantBits)
                    }
                    .array()

            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val combined = namespaceBytes + nameBytes

            val digest = MessageDigest.getInstance("SHA-1").digest(combined)

            // Set version (5) and variant bits
            digest[6] = (digest[6].toInt() and 0x0F or 0x50).toByte()
            digest[8] = (digest[8].toInt() and 0x3F or 0x80).toByte()

            val bb = ByteBuffer.wrap(digest)
            return UUID(bb.long, bb.long)
        }
    }

    suspend fun createFile(userUuid: UUID, payload: UploadPayload): Either<DBException, Upload> =
        either {
            db.transaction {
                // Generate deterministic UUID based on file content
                // Note: Not including userUuid to allow file deduplication across users
                val uuidInput =
                    listOf(
                            "h:${payload.hash}",
                            "n:${payload.originalname}",
                            "a:${payload.altText ?: ""}",
                            "w:${payload.imageWidth ?: ""}",
                            "h:${payload.imageHeight ?: ""}",
                            "m:${payload.mimetype}",
                        )
                        .joinToString("/")

                val uuid = generateUuidV5(uuidInput).toString()

                // Check if already exists for this user
                val existing =
                    query("SELECT * FROM uploads WHERE uuid = ? AND user_uuid = ?") {
                        setString(1, uuid)
                        setString(2, userUuid.toString())
                        executeQuery().safe().firstOrNull { rs ->
                            Upload(
                                uuid = rs.getString("uuid"),
                                hash = rs.getString("hash"),
                                originalname = rs.getString("originalname"),
                                mimetype = rs.getString("mimetype"),
                                size = rs.getLong("size"),
                                altText = rs.getString("altText"),
                                imageWidth = rs.getObject("imageWidth") as? Int,
                                imageHeight = rs.getObject("imageHeight") as? Int,
                                createdAt = Instant.ofEpochMilli(rs.getLong("createdAt")),
                            )
                        }
                    }

                if (existing != null) {
                    return@transaction existing
                }

                val now = db.clock.instant()
                val upload =
                    Upload(
                        uuid = uuid,
                        hash = payload.hash,
                        originalname = payload.originalname,
                        mimetype = payload.mimetype,
                        size = payload.size,
                        altText = payload.altText,
                        imageWidth = payload.imageWidth,
                        imageHeight = payload.imageHeight,
                        createdAt = now,
                    )

                query(
                    """
                    INSERT INTO uploads
                        (uuid, user_uuid, hash, originalname, mimetype, size, altText, imageWidth, imageHeight, createdAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """
                        .trimIndent()
                ) {
                    setString(1, upload.uuid)
                    setString(2, userUuid.toString())
                    setString(3, upload.hash)
                    setString(4, upload.originalname)
                    setString(5, upload.mimetype)
                    setLong(6, upload.size)
                    setString(7, upload.altText)
                    upload.imageWidth?.let { setInt(8, it) } ?: setNull(8, java.sql.Types.INTEGER)
                    upload.imageHeight?.let { setInt(9, it) } ?: setNull(9, java.sql.Types.INTEGER)
                    setLong(10, upload.createdAt.toEpochMilli())
                    execute()
                    Unit
                }

                upload
            }
        }

    suspend fun getFileByUuid(userUuid: UUID, uuid: String): Either<DBException, Upload?> = either {
        db.query("SELECT * FROM uploads WHERE uuid = ? AND user_uuid = ?") {
            setString(1, uuid)
            setString(2, userUuid.toString())
            executeQuery().safe().firstOrNull { rs ->
                Upload(
                    uuid = rs.getString("uuid"),
                    hash = rs.getString("hash"),
                    originalname = rs.getString("originalname"),
                    mimetype = rs.getString("mimetype"),
                    size = rs.getLong("size"),
                    altText = rs.getString("altText"),
                    imageWidth = rs.getObject("imageWidth") as? Int,
                    imageHeight = rs.getObject("imageHeight") as? Int,
                    createdAt = Instant.ofEpochMilli(rs.getLong("createdAt")),
                )
            }
        }
    }
}
