package socialpublish.backend.db

import arrow.core.Either
import arrow.core.raise.either
import java.util.UUID
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

class PostsDatabase(private val docs: DocumentsDatabase) {
    suspend fun create(
        payload: PostPayload,
        targets: List<String>,
        userUuid: UUID? = null,
    ): Either<DBException, Post> = either {
        val payloadJson = json.encodeToString(PostPayload.serializer(), payload)
        val row =
            docs
                .createOrUpdate(
                    kind = "post",
                    payload = payloadJson,
                    tags = targets.map { Tag(it, "target") },
                    userUuid = userUuid,
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

    suspend fun getAll(userUuid: UUID? = null): Either<DBException, List<Post>> = either {
        val rows = docs.getAll("post", DocumentsDatabase.OrderBy.CREATED_AT_DESC, userUuid).bind()
        rows.map { row ->
            val payload = json.decodeFromString<PostPayload>(row.payload)
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

    suspend fun searchByUuid(uuid: String): Either<DBException, Post?> = either {
        val row = docs.searchByUuid(uuid).bind() ?: return@either null
        val payload = json.decodeFromString<PostPayload>(row.payload)
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
