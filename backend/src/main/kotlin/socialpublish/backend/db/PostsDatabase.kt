@file:MustUseReturnValues

package socialpublish.backend.db

import arrow.core.Either
import arrow.core.raise.either
import socialpublish.backend.common.jsonCommon

class PostsDatabase(private val docs: DocumentsDatabase) {
    context(_: UserSession)
    suspend fun create(payload: PostPayload, targets: List<String>): Either<DBException, Post> =
        either {
            createForCurrentContext(payload, targets).bind()
        }

    suspend fun create(
        payload: PostPayload,
        targets: List<String>,
        userUuid: UUIDv7,
    ): Either<DBException, Post> = either {
        val payloadJson = jsonCommon.encodeToString(PostPayload.serializer(), payload)
        val row =
            docs
                .createOrUpdate(
                    kind = "post",
                    payload = payloadJson,
                    userUuid = userUuid,
                    tags = targets.map { Tag(it, "target") },
                )
                .bind()
        Post(
            uuid = row.uuid,
            createdAt = row.createdAt,
            targets = targets,
            content = payload.content,
            link = payload.link,
            tags = payload.tags,
            language = payload.language,
            images = payload.images,
        )
    }

    context(_: UserSession)
    private suspend fun createForCurrentContext(
        payload: PostPayload,
        targets: List<String>,
    ): Either<DBException, Post> = either {
        val payloadJson = jsonCommon.encodeToString(PostPayload.serializer(), payload)
        val row =
            docs
                .createOrUpdate(
                    kind = "post",
                    payload = payloadJson,
                    tags = targets.map { Tag(it, "target") },
                )
                .bind()
        Post(
            uuid = row.uuid,
            createdAt = row.createdAt,
            targets = targets,
            content = payload.content,
            link = payload.link,
            tags = payload.tags,
            language = payload.language,
            images = payload.images,
        )
    }

    context(_: UserSession)
    suspend fun getAll(): Either<DBException, List<Post>> = either {
        val rows = docs.getAll("post", DocumentsDatabase.OrderBy.CREATED_AT_DESC).bind()
        rowsToPosts(rows)
    }

    suspend fun getAllForUser(userUuid: UUIDv7): Either<DBException, List<Post>> = either {
        val rows =
            docs.getAllForUser("post", userUuid, DocumentsDatabase.OrderBy.CREATED_AT_DESC).bind()
        rowsToPosts(rows)
    }

    private fun rowsToPosts(rows: List<Document>): List<Post> = rows.map { row ->
        val payload = jsonCommon.decodeFromString<PostPayload>(row.payload)
        Post(
            uuid = row.uuid,
            createdAt = row.createdAt,
            targets = row.tags.filter { it.kind == "target" }.map { it.name },
            content = payload.content,
            link = payload.link,
            tags = payload.tags,
            language = payload.language,
            images = payload.images,
        )
    }

    context(session: UserSession)
    suspend fun searchByUuid(uuid: String): Either<DBException, Post?> = either {
        val row = docs.searchByUuidForCurrentUser(uuid).bind() ?: return@either null
        val payload = jsonCommon.decodeFromString<PostPayload>(row.payload)
        Post(
            uuid = row.uuid,
            createdAt = row.createdAt,
            targets = row.tags.filter { it.kind == "target" }.map { it.name },
            content = payload.content,
            link = payload.link,
            tags = payload.tags,
            language = payload.language,
            images = payload.images,
        )
    }

    suspend fun searchByUuidForUser(uuid: String, userUuid: UUIDv7): Either<DBException, Post?> =
        either {
            val row = docs.searchByUuid(uuid).bind() ?: return@either null
            if (row.userUuid != userUuid) return@either null
            val payload = jsonCommon.decodeFromString<PostPayload>(row.payload)
            Post(
                uuid = row.uuid,
                createdAt = row.createdAt,
                targets = row.tags.filter { it.kind == "target" }.map { it.name },
                content = payload.content,
                link = payload.link,
                tags = payload.tags,
                language = payload.language,
                images = payload.images,
            )
        }
}
