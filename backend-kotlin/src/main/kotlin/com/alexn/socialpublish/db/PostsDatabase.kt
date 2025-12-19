package com.alexn.socialpublish.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jdbi.v3.core.Jdbi
import java.time.Instant

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class PostPayload(
    val content: String,
    val link: String? = null,
    val tags: List<String>? = null,
    val language: String? = null,
    val images: List<String>? = null
)

data class Post(
    val uuid: String,
    val targets: List<String>,
    val createdAt: Instant,
    val content: String,
    val link: String? = null,
    val tags: List<String>? = null,
    val language: String? = null,
    val images: List<String>? = null
)

class PostsDatabase(private val docs: DocumentsDatabase) {
    
    fun create(payload: PostPayload, targets: List<String>): Post {
        val payloadJson = json.encodeToString(PostPayload.serializer(), payload)
        val row = docs.createOrUpdate(
            kind = "post",
            payload = payloadJson,
            tags = targets.map { Tag(it, "target") }
        )
        return Post(
            uuid = row.uuid,
            createdAt = row.createdAt,
            targets = targets,
            content = payload.content,
            link = payload.link,
            tags = payload.tags,
            language = payload.language,
            images = payload.images
        )
    }
    
    fun getAll(): List<Post> {
        val rows = docs.getAll("post", "created_at DESC")
        return rows.map { row ->
            val payload = json.decodeFromString<PostPayload>(row.payload)
            Post(
                uuid = row.uuid,
                createdAt = row.createdAt,
                targets = row.tags.filter { it.kind == "target" }.map { it.name },
                content = payload.content,
                link = payload.link,
                tags = payload.tags,
                language = payload.language,
                images = payload.images
            )
        }
    }
    
    fun searchByUuid(uuid: String): Post? {
        val row = docs.searchByUuid(uuid) ?: return null
        val payload = json.decodeFromString<PostPayload>(row.payload)
        return Post(
            uuid = row.uuid,
            createdAt = row.createdAt,
            targets = row.tags.filter { it.kind == "target" }.map { it.name },
            content = payload.content,
            link = payload.link,
            tags = payload.tags,
            language = payload.language,
            images = payload.images
        )
    }
}
