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
    val userUuid: UUID,
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
    val userUuid: UUID,
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

    suspend fun createFile(payload: UploadPayload): Either<DBException, Upload> = either {
        db.transaction {
            val uuidInput =
                listOf(
                        "u:${payload.userUuid}",
                        "h:${payload.hash}",
                        "n:${payload.originalname}",
                        "a:${payload.altText ?: ""}",
                        "w:${payload.imageWidth ?: ""}",
                        "h:${payload.imageHeight ?: ""}",
                        "m:${payload.mimetype}",
                    )
                    .joinToString("/")

            val uuid = generateUuidV5(uuidInput).toString()

            val existing =
                query("SELECT * FROM uploads WHERE uuid = ?") {
                    setString(1, uuid)
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
                            userUuid = UUID.fromString(rs.getString("user_uuid")),
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
                    userUuid = payload.userUuid,
                    createdAt = now,
                )

            query(
                """
                INSERT INTO uploads
                    (uuid, hash, originalname, mimetype, size, altText, imageWidth, imageHeight, user_uuid, createdAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent()
            ) {
                setString(1, upload.uuid)
                setString(2, upload.hash)
                setString(3, upload.originalname)
                setString(4, upload.mimetype)
                setLong(5, upload.size)
                setString(6, upload.altText)
                upload.imageWidth?.let { setInt(7, it) } ?: setNull(7, java.sql.Types.INTEGER)
                upload.imageHeight?.let { setInt(8, it) } ?: setNull(8, java.sql.Types.INTEGER)
                setString(9, upload.userUuid.toString())
                setLong(10, upload.createdAt.toEpochMilli())
                execute()
                Unit
            }

            upload
        }
    }

    suspend fun getFileByUuid(uuid: String): Either<DBException, Upload?> = either {
        db.query("SELECT * FROM uploads WHERE uuid = ?") {
            setString(1, uuid)
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
                    userUuid = UUID.fromString(rs.getString("user_uuid")),
                    createdAt = Instant.ofEpochMilli(rs.getLong("createdAt")),
                )
            }
        }
    }

    suspend fun getFileByUuidForUser(uuid: String, userUuid: UUID): Either<DBException, Upload?> =
        either {
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
                        userUuid = UUID.fromString(rs.getString("user_uuid")),
                        createdAt = Instant.ofEpochMilli(rs.getLong("createdAt")),
                    )
                }
            }
        }
}
