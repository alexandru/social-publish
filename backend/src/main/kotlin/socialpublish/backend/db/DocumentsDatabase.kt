package socialpublish.backend.db

import arrow.core.Either
import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

class DocumentsDatabase(private val db: Database) {
    private suspend fun SafeConnection.setDocumentTags(documentUuid: String, tags: List<Tag>) {
        query("DELETE FROM document_tags WHERE document_uuid = ?") {
            setString(1, documentUuid)
            execute()
            Unit
        }
        tags.forEach { tag ->
            query("INSERT INTO document_tags (document_uuid, name, kind) VALUES (?, ?, ?)") {
                setString(1, documentUuid)
                setString(2, tag.name)
                setString(3, tag.kind)
                execute()
                Unit
            }
        }
        if (tags.isNotEmpty()) {
            logger.info {
                "Tags for document $documentUuid: ${tags.joinToString(", ") { it.name }}"
            }
        }
    }

    private suspend fun SafeConnection.getDocumentTags(documentUuid: String): List<Tag> {
        return query("SELECT name, kind FROM document_tags WHERE document_uuid = ?") {
            setString(1, documentUuid)
            executeQuery().safe().toList { rs -> Tag(rs.getString("name"), rs.getString("kind")) }
        }
    }

    suspend fun createOrUpdate(
        kind: String,
        payload: String,
        userUuid: UUID,
        searchKey: String? = null,
        tags: List<Tag> = emptyList(),
    ): Either<DBException, Document> = either {
        db.transaction {
            val existing =
                searchKey?.let { key ->
                    val docData =
                        query("SELECT * FROM documents WHERE search_key = ? AND user_uuid = ?") {
                            setString(1, key)
                            setString(2, userUuid.toString())
                            executeQuery().safe().firstOrNull { rs ->
                                Triple(
                                    rs.getString("uuid"),
                                    rs.getString("payload"),
                                    Instant.ofEpochMilli(rs.getLong("created_at")),
                                )
                            }
                        }
                    docData?.let { (uuid, existingPayload, createdAt) ->
                        val tagsForRow = getDocumentTags(uuid)
                        Document(
                            uuid = uuid,
                            kind = kind,
                            payload = existingPayload,
                            searchKey = key,
                            tags = tagsForRow,
                            userUuid = userUuid,
                            createdAt = createdAt,
                        )
                    }
                }

            if (existing != null) {
                val updated =
                    query(
                        "UPDATE documents SET payload = ? WHERE search_key = ? AND user_uuid = ?"
                    ) {
                        setString(1, payload)
                        setString(2, searchKey)
                        setString(3, userUuid.toString())
                        executeUpdate()
                    }
                logger.info { "Updated document: $updated" }
                setDocumentTags(existing.uuid, tags)
                if (updated > 0) {
                    return@transaction existing.copy(payload = payload, tags = tags)
                }
                logger.warn { "Failed to update document with search key $searchKey" }
            }

            val uuid = UUID.randomUUID().toString()
            val finalSearchKey = searchKey ?: "$kind:$uuid"
            val now = db.clock.instant()

            query(
                "INSERT INTO documents (uuid, search_key, kind, payload, user_uuid, created_at) VALUES (?, ?, ?, ?, ?, ?)"
            ) {
                setString(1, uuid)
                setString(2, finalSearchKey)
                setString(3, kind)
                setString(4, payload)
                setString(5, userUuid.toString())
                setLong(6, now.toEpochMilli())
                execute()
                Unit
            }
            setDocumentTags(uuid, tags)

            Document(uuid, finalSearchKey, kind, tags, payload, userUuid, now)
        }
    }

    suspend fun searchByKey(searchKey: String): Either<DBException, Document?> = either {
        db.transaction {
            val docData =
                query("SELECT * FROM documents WHERE search_key = ?") {
                    setString(1, searchKey)
                    executeQuery().safe().firstOrNull { rs ->
                        Pair(
                            rs.getString("uuid"),
                            Document(
                                uuid = rs.getString("uuid"),
                                kind = rs.getString("kind"),
                                payload = rs.getString("payload"),
                                searchKey = rs.getString("search_key"),
                                tags = emptyList(),
                                userUuid = UUID.fromString(rs.getString("user_uuid")),
                                createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                            ),
                        )
                    }
                }
            docData?.let { (uuid, doc) ->
                val tags = getDocumentTags(uuid)
                doc.copy(tags = tags)
            }
        }
    }

    suspend fun searchByUuid(uuid: String): Either<DBException, Document?> = either {
        db.transaction {
            val doc =
                query("SELECT * FROM documents WHERE uuid = ?") {
                    setString(1, uuid)
                    executeQuery().safe().firstOrNull { rs ->
                        Document(
                            uuid = rs.getString("uuid"),
                            kind = rs.getString("kind"),
                            payload = rs.getString("payload"),
                            searchKey = rs.getString("search_key"),
                            tags = emptyList(),
                            userUuid = UUID.fromString(rs.getString("user_uuid")),
                            createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                        )
                    }
                }
            doc?.let {
                val tags = getDocumentTags(uuid)
                it.copy(tags = tags)
            }
        }
    }

    enum class OrderBy(val sql: String) {
        CREATED_AT_DESC("created_at DESC")
    }

    /**
     * Return documents of the given [kind]. When [userUuid] is provided only that user's documents
     * are returned; when null, documents for all users are returned (e.g. for the public RSS feed).
     */
    suspend fun getAll(
        kind: String,
        orderBy: OrderBy = OrderBy.CREATED_AT_DESC,
        userUuid: UUID? = null,
    ): Either<DBException, List<Document>> = either {
        db.transaction {
            val (sql, setParams) =
                if (userUuid != null) {
                    Pair(
                        "SELECT * FROM documents WHERE kind = ? AND user_uuid = ? ORDER BY ${orderBy.sql}",
                        { stmt: java.sql.PreparedStatement ->
                            stmt.setString(1, kind)
                            stmt.setString(2, userUuid.toString())
                        },
                    )
                } else {
                    Pair(
                        "SELECT * FROM documents WHERE kind = ? ORDER BY ${orderBy.sql}",
                        { stmt: java.sql.PreparedStatement -> stmt.setString(1, kind) },
                    )
                }
            val docs =
                query(sql) {
                    setParams(this)
                    executeQuery().safe().toList { rs ->
                        Document(
                            uuid = rs.getString("uuid"),
                            kind = rs.getString("kind"),
                            payload = rs.getString("payload"),
                            searchKey = rs.getString("search_key"),
                            tags = emptyList(),
                            userUuid = UUID.fromString(rs.getString("user_uuid")),
                            createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                        )
                    }
                }
            docs.map { doc ->
                val tags = getDocumentTags(doc.uuid)
                doc.copy(tags = tags)
            }
        }
    }
}
