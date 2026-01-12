package com.alexn.socialpublish.db

import org.jdbi.v3.core.Jdbi
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

class FilesDatabase(private val jdbi: Jdbi) {
    companion object {
        private val UUID_NAMESPACE = UUID.fromString("5b9ba0d0-8825-4c51-a34e-f849613dbcac")

        /**
         * Generate UUID v5 from string (deterministic UUID based on namespace and name)
         */
        fun generateUuidV5(
            name: String,
            namespace: UUID = UUID_NAMESPACE,
        ): UUID {
            val namespaceBytes =
                ByteBuffer.allocate(16).apply {
                    putLong(namespace.mostSignificantBits)
                    putLong(namespace.leastSignificantBits)
                }.array()

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

    fun createFile(payload: UploadPayload): Upload {
        return jdbi.inTransaction<Upload, Exception> { handle ->
            // Generate deterministic UUID
            val uuidInput =
                listOf(
                    "h:${payload.hash}",
                    "n:${payload.originalname}",
                    "a:${payload.altText ?: ""}",
                    "w:${payload.imageWidth ?: ""}",
                    "h:${payload.imageHeight ?: ""}",
                    "m:${payload.mimetype}",
                ).joinToString("/")

            val uuid = generateUuidV5(uuidInput).toString()

            // Check if already exists
            val existing =
                handle.createQuery("SELECT * FROM uploads WHERE uuid = ?")
                    .bind(0, uuid)
                    .mapToMap()
                    .findOne()
                    .orElse(null)

            if (existing != null) {
                return@inTransaction Upload(
                    uuid = existing["uuid"] as String,
                    hash = existing["hash"] as String,
                    originalname = existing["originalname"] as String,
                    mimetype = existing["mimetype"] as String,
                    size = existing["size"] as Long,
                    altText = existing["altText"] as String?,
                    imageWidth = existing["imageWidth"] as Int?,
                    imageHeight = existing["imageHeight"] as Int?,
                    createdAt = Instant.ofEpochMilli(existing["createdAt"] as Long),
                )
            }

            val now = Instant.now()
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

            handle.execute(
                """
                INSERT INTO uploads
                    (uuid, hash, originalname, mimetype, size, altText, imageWidth, imageHeight, createdAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                upload.uuid,
                upload.hash,
                upload.originalname,
                upload.mimetype,
                upload.size,
                upload.altText,
                upload.imageWidth,
                upload.imageHeight,
                upload.createdAt.toEpochMilli(),
            )

            upload
        }
    }

    fun getFileByUuid(uuid: String): Upload? {
        return jdbi.withHandle<Upload?, Exception> { handle ->
            val row =
                handle.createQuery("SELECT * FROM uploads WHERE uuid = ?")
                    .bind(0, uuid)
                    .mapToMap()
                    .findOne()
                    .orElse(null) ?: return@withHandle null

            Upload(
                uuid = row["uuid"] as String,
                hash = row["hash"] as String,
                originalname = row["originalname"] as String,
                mimetype = row["mimetype"] as String,
                size = row["size"] as Long,
                altText = row["altText"] as String?,
                imageWidth = row["imageWidth"] as Int?,
                imageHeight = row["imageHeight"] as Int?,
                createdAt = Instant.ofEpochMilli(row["createdAt"] as Long),
            )
        }
    }
}

private fun ByteBuffer.putUUID(uuid: UUID): ByteBuffer {
    putLong(uuid.mostSignificantBits)
    putLong(uuid.leastSignificantBits)
    return this
}
