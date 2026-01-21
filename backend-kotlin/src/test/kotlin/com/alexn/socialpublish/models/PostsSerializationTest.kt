package com.alexn.socialpublish.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.Test

/**
 * Tests to ensure JSON serialization matches the legacy backend format.
 *
 * The legacy backend (./backend/) returns plain JSON objects without type discriminators. This test
 * ensures backward compatibility.
 */
@OptIn(ExperimentalSerializationApi::class)
class PostsSerializationTest {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "#type"
        classDiscriminatorMode = kotlinx.serialization.json.ClassDiscriminatorMode.NONE
        serializersModule = SerializersModule {
            polymorphic(NewPostResponse::class) {
                subclass(NewRssPostResponse::class)
                subclass(NewMastodonPostResponse::class)
                subclass(NewBlueSkyPostResponse::class)
                subclass(NewTwitterPostResponse::class)
            }
        }
    }

    @Test
    fun `NewRssPostResponse should serialize without type discriminator`() {
        val response =
            NewRssPostResponse(
                uri = "http://localhost:3000/rss/123e4567-e89b-12d3-a456-426614174000"
            )

        val jsonString = json.encodeToString<NewRssPostResponse>(response)

        println("Serialized JSON: $jsonString")

        // Check that required fields are present
        assert(jsonString.contains("\"module\":\"rss\""))
        assert(
            jsonString.contains(
                "\"uri\":\"http://localhost:3000/rss/123e4567-e89b-12d3-a456-426614174000\""
            )
        )
        // Ensure no type discriminator
        assert(!jsonString.contains("\"type\""))
        assert(!jsonString.contains("NewRssPostResponse"))
    }

    @Test
    fun `NewMastodonPostResponse should serialize without type discriminator`() {
        val response = NewMastodonPostResponse(uri = "https://mastodon.social/@user/123456789")

        val jsonString = json.encodeToString<NewMastodonPostResponse>(response)

        assert(jsonString.contains("\"module\":\"mastodon\""))
        assert(jsonString.contains("\"uri\":\"https://mastodon.social/@user/123456789\""))
        assert(!jsonString.contains("\"type\""))
    }

    @Test
    fun `NewBlueSkyPostResponse should serialize without type discriminator`() {
        val response =
            NewBlueSkyPostResponse(
                uri = "at://did:plc:abc123/app.bsky.feed.post/xyz789",
                cid = "bafyreiabc123",
            )

        val jsonString = json.encodeToString<NewBlueSkyPostResponse>(response)

        assert(jsonString.contains("\"module\":\"bluesky\""))
        assert(jsonString.contains("\"uri\":\"at://did:plc:abc123/app.bsky.feed.post/xyz789\""))
        assert(jsonString.contains("\"cid\":\"bafyreiabc123\""))
        assert(!jsonString.contains("\"type\""))
    }

    @Test
    fun `NewTwitterPostResponse should serialize without type discriminator`() {
        val response = NewTwitterPostResponse(id = "1234567890")

        val jsonString = json.encodeToString<NewTwitterPostResponse>(response)

        assert(jsonString.contains("\"module\":\"twitter\""))
        assert(jsonString.contains("\"id\":\"1234567890\""))
        assert(!jsonString.contains("\"type\""))
    }

    @Test
    fun `Map with single RSS response should serialize correctly`() {
        val response =
            mapOf(
                "rss" to
                    NewRssPostResponse(
                        uri = "http://localhost:3000/rss/123e4567-e89b-12d3-a456-426614174000"
                    )
            )

        val jsonString = json.encodeToString<Map<String, NewPostResponse>>(response)

        assert(jsonString.contains("\"rss\""))
        assert(jsonString.contains("\"module\":\"rss\""))
        assert(
            jsonString.contains(
                "\"uri\":\"http://localhost:3000/rss/123e4567-e89b-12d3-a456-426614174000\""
            )
        )
        assert(!jsonString.contains("\"type\""))
    }

    @Test
    fun `Map with multiple responses should serialize correctly`() {
        val response =
            mapOf(
                "rss" to
                    NewRssPostResponse(
                        uri = "http://localhost:3000/rss/123e4567-e89b-12d3-a456-426614174000"
                    ),
                "mastodon" to
                    NewMastodonPostResponse(uri = "https://mastodon.social/@user/123456789"),
                "bluesky" to
                    NewBlueSkyPostResponse(
                        uri = "at://did:plc:abc123/app.bsky.feed.post/xyz789",
                        cid = "bafyreiabc123",
                    ),
            )

        val jsonString = json.encodeToString<Map<String, NewPostResponse>>(response)

        // Check that all expected keys are present
        assert(jsonString.contains("\"rss\""))
        assert(jsonString.contains("\"mastodon\""))
        assert(jsonString.contains("\"bluesky\""))

        // Check that module fields are present (not type discriminators)
        assert(jsonString.contains("\"module\":\"rss\""))
        assert(jsonString.contains("\"module\":\"mastodon\""))
        assert(jsonString.contains("\"module\":\"bluesky\""))

        // Ensure no type discriminator is added
        assert(!jsonString.contains("\"type\""))
        assert(!jsonString.contains("NewRssPostResponse"))
        assert(!jsonString.contains("NewMastodonPostResponse"))
        assert(!jsonString.contains("NewBlueSkyPostResponse"))
    }

    @Test
    fun `Polymorphic serialization should work with NewPostResponse base class`() {
        val responses: List<NewPostResponse> =
            listOf(
                NewRssPostResponse(uri = "http://localhost:3000/rss/123"),
                NewMastodonPostResponse(uri = "https://mastodon.social/@user/456"),
                NewBlueSkyPostResponse(uri = "at://did:plc:abc/post/789", cid = "bafyabc"),
            )

        val jsonString = json.encodeToString<List<NewPostResponse>>(responses)

        // Verify all module fields are present
        assert(jsonString.contains("\"module\":\"rss\""))
        assert(jsonString.contains("\"module\":\"mastodon\""))
        assert(jsonString.contains("\"module\":\"bluesky\""))
    }
}
