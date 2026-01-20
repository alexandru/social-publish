package com.alexn.socialpublish.db

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.jdbi.v3.core.Jdbi

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

  suspend fun createFile(payload: UploadPayload): Upload {
    return dbInterruptible {
      jdbi.inTransaction<Upload, Exception> { handle ->
        // Generate deterministic UUID
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

        // Check if already exists
        val existing =
          handle
            .createQuery("SELECT * FROM uploads WHERE uuid = ?")
            .bind(0, uuid)
            .mapToMap()
            .findOne()
            .orElse(null)

        if (existing != null) {
          val row = existing.normalizeKeys()
          val sizeVal = requireNotNull(row["size"] as? Number).toLong()
          val createdAtMillis = requireNotNull(row["createdat"] as? Number).toLong()

          return@inTransaction Upload(
            uuid = row["uuid"] as String,
            hash = row["hash"] as String,
            originalname = row["originalname"] as String,
            mimetype = row["mimetype"] as String,
            size = sizeVal,
            altText = row["alttext"] as String?,
            imageWidth = (row["imagewidth"] as? Number)?.toInt(),
            imageHeight = (row["imageheight"] as? Number)?.toInt(),
            createdAt = Instant.ofEpochMilli(createdAtMillis),
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
                    """
            .trimIndent(),
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
  }

  suspend fun getFileByUuid(uuid: String): Upload? {
    return dbInterruptible {
      jdbi.withHandle<Upload?, Exception> { handle ->
        // Some JDBC drivers / sqlite variants may normalize column names; fetch all rows and match
        // by uuid string
        val rows = handle.createQuery("SELECT * FROM uploads").mapToMap().list()
        val normalizedRows = rows.map { it.normalizeKeys() }
        val row = normalizedRows.firstOrNull { r -> r["uuid"]?.toString() == uuid }

        if (row == null) {
          return@withHandle null
        }

        val sizeVal = (row["size"] as? Number)?.toLong() ?: return@withHandle null
        val createdAtMillis = (row["createdat"] as? Number)?.toLong() ?: return@withHandle null

        Upload(
          uuid = row["uuid"] as String,
          hash = row["hash"] as String,
          originalname = row["originalname"] as String,
          mimetype = row["mimetype"] as String,
          size = sizeVal,
          altText = row["alttext"] as String?,
          imageWidth = (row["imagewidth"] as? Number)?.toInt(),
          imageHeight = (row["imageheight"] as? Number)?.toInt(),
          createdAt = Instant.ofEpochMilli(createdAtMillis),
        )
      }
    }
  }
}

private fun Map<String, Any?>.normalizeKeys(): Map<String, Any?> =
  this.mapKeys { it.key.lowercase() }

private fun ByteBuffer.putUUID(uuid: UUID): ByteBuffer {
  putLong(uuid.mostSignificantBits)
  putLong(uuid.leastSignificantBits)
  return this
}
