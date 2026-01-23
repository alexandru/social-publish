package socialpublish.backend.db

import arrow.core.Either
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.UUID
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi

private val logger = KotlinLogging.logger {}

class DocumentsDatabase(private val jdbi: Jdbi) {
    private fun setDocumentTags(handle: Handle, documentUuid: String, tags: List<Tag>) {
        handle.execute("DELETE FROM document_tags WHERE document_uuid = ?", documentUuid)
        tags.forEach { tag ->
            handle.execute(
                "INSERT INTO document_tags (document_uuid, name, kind) VALUES (?, ?, ?)",
                documentUuid,
                tag.name,
                tag.kind,
            )
        }
        if (tags.isNotEmpty()) {
            logger.info {
                "Tags for document $documentUuid: ${tags.joinToString(", ") { it.name }}"
            }
        }
    }

    private fun getDocumentTags(handle: Handle, documentUuid: String): List<Tag> {
        return handle
            .createQuery("SELECT name, kind FROM document_tags WHERE document_uuid = ?")
            .bind(0, documentUuid)
            .map { rs, _ -> Tag(rs.getString("name"), rs.getString("kind")) }
            .list()
    }

    suspend fun createOrUpdate(
        kind: String,
        payload: String,
        searchKey: String? = null,
        tags: List<Tag> = emptyList(),
    ): Either<DBException, Document> =
        jdbi.safeTransaction { handle ->
            val existing =
                searchKey?.let { key ->
                    handle
                        .createQuery("SELECT * FROM documents WHERE search_key = ?")
                        .bind(0, key)
                        .mapToMap()
                        .findOne()
                        .orElse(null)
                        ?.let { row ->
                            val tagsForRow = getDocumentTags(handle, row["uuid"] as String)
                            Document(
                                uuid = row["uuid"] as String,
                                kind = row["kind"] as String,
                                payload = row["payload"] as String,
                                searchKey = row["search_key"] as String,
                                tags = tagsForRow,
                                createdAt = Instant.ofEpochMilli(row["created_at"] as Long),
                            )
                        }
                }
            if (existing != null) {
                val updated =
                    handle.execute(
                        "UPDATE documents SET payload = ? WHERE search_key = ?",
                        payload,
                        searchKey,
                    )
                logger.info { "Updated document: $updated" }
                setDocumentTags(handle, existing.uuid, tags)
                if (updated > 0) {
                    return@safeTransaction existing.copy(payload = payload, tags = tags)
                }
                logger.warn { "Failed to update document with search key $searchKey" }
            }

            val uuid = UUID.randomUUID().toString()
            val finalSearchKey = searchKey ?: "$kind:$uuid"
            val now = Instant.now()

            handle.execute(
                "INSERT INTO documents (uuid, search_key, kind, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                uuid,
                finalSearchKey,
                kind,
                payload,
                now.toEpochMilli(),
            )
            setDocumentTags(handle, uuid, tags)

            Document(uuid, finalSearchKey, kind, tags, payload, now)
        }

    suspend fun searchByKey(searchKey: String): Either<DBException, Document?> =
        jdbi.safeHandle { handle ->
            val row =
                handle
                    .createQuery("SELECT * FROM documents WHERE search_key = ?")
                    .bind(0, searchKey)
                    .mapToMap()
                    .findOne()
                    .orElse(null) ?: return@safeHandle null

            val tags = getDocumentTags(handle, row["uuid"] as String)
            Document(
                uuid = row["uuid"] as String,
                kind = row["kind"] as String,
                payload = row["payload"] as String,
                searchKey = row["search_key"] as String,
                tags = tags,
                createdAt = Instant.ofEpochMilli(row["created_at"] as Long),
            )
        }

    suspend fun searchByUuid(uuid: String): Either<DBException, Document?> =
        jdbi.safeHandle { handle ->
            val row =
                handle
                    .createQuery("SELECT * FROM documents WHERE uuid = ?")
                    .bind(0, uuid)
                    .mapToMap()
                    .findOne()
                    .orElse(null) ?: return@safeHandle null

            val tags = getDocumentTags(handle, row["uuid"] as String)
            Document(
                uuid = row["uuid"] as String,
                kind = row["kind"] as String,
                payload = row["payload"] as String,
                searchKey = row["search_key"] as String,
                tags = tags,
                createdAt = Instant.ofEpochMilli(row["created_at"] as Long),
            )
        }

    enum class OrderBy(val sql: String) {
        CREATED_AT_DESC("created_at DESC")
        // CREATED_AT_ASC("created_at ASC"),
    }

    suspend fun getAll(
        kind: String,
        orderBy: OrderBy = OrderBy.CREATED_AT_DESC,
    ): Either<DBException, List<Document>> =
        jdbi.safeHandle { handle ->
            val rows =
                handle
                    .createQuery("SELECT * FROM documents WHERE kind = ? ORDER BY ${orderBy.sql}")
                    .bind(0, kind)
                    .mapToMap()
                    .list()

            rows.map { row ->
                val tags = getDocumentTags(handle, row["uuid"] as String)
                Document(
                    uuid = row["uuid"] as String,
                    kind = row["kind"] as String,
                    payload = row["payload"] as String,
                    searchKey = row["search_key"] as String,
                    tags = tags,
                    createdAt = Instant.ofEpochMilli(row["created_at"] as Long),
                )
            }
        }
}
