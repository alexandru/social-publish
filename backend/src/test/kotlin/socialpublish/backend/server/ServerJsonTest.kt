package socialpublish.backend.server

import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import socialpublish.backend.clients.bluesky.BlueskyBlobUploadResponse
import socialpublish.backend.clients.bluesky.BlueskyCreateRecordRequest
import socialpublish.backend.clients.bluesky.BlueskyCreateSessionRequest
import socialpublish.backend.clients.bluesky.BlueskyPostResponse
import socialpublish.backend.clients.bluesky.BlueskySessionResponse
import socialpublish.backend.clients.linkedin.LinkedInRegisterUploadRequest
import socialpublish.backend.clients.linkedin.LinkedInRegisterUploadResponse
import socialpublish.backend.clients.linkedin.LinkedInTokenResponse
import socialpublish.backend.clients.linkedin.LinkedInUserProfile
import socialpublish.backend.clients.linkedin.UgcPostRequest
import socialpublish.backend.clients.llm.GenerateAltTextRequest
import socialpublish.backend.clients.llm.GenerateAltTextResponse
import socialpublish.backend.clients.llm.OpenAiChatRequest
import socialpublish.backend.clients.llm.OpenAiChatResponse
import socialpublish.backend.clients.mastodon.MastodonMediaResponse
import socialpublish.backend.clients.mastodon.MastodonStatusResponse
import socialpublish.backend.clients.twitter.TwitterMediaResponse
import socialpublish.backend.clients.twitter.TwitterPostResponse
import socialpublish.backend.common.CompositeErrorWithDetails
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewBlueSkyPostResponse
import socialpublish.backend.common.NewFeedPostResponse
import socialpublish.backend.common.NewLinkedInPostResponse
import socialpublish.backend.common.NewMastodonPostResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.NewPostResponseSerializer
import socialpublish.backend.common.NewTwitterPostResponse
import socialpublish.backend.db.Post
import socialpublish.backend.modules.FileAltTextPatch
import socialpublish.backend.modules.FileUploadResponse
import socialpublish.backend.server.routes.AccountSettingsView
import socialpublish.backend.server.routes.LinkedInStatusResponse
import socialpublish.backend.server.routes.LoginRequest
import socialpublish.backend.server.routes.LoginResponse
import socialpublish.backend.server.routes.TwitterStatusResponse
import socialpublish.backend.server.routes.UserResponse
import socialpublish.backend.server.routes.UserSettingsPatch

/**
 * Ktor's content negotiation resolves serializers at runtime
 * (serializerForTypeInfo: SerializersModule lookup first, reflection as
 * fallback). Runtime reflection needs GraalVM metadata in native images, so:
 * - types served through the API must be registered in serverJson()'s
 *   SerializersModule (static lookup), and
 * - client DTO types are looked up reflectively and rely on
 *   reachability-metadata.json. Under nativeTest this test fails for any type
 *   missing from either, surfacing would-be production 500s (e.g. UserResponse)
 *   before deploy. When adding a serializable type to the API or clients, add
 *   it to the matching list here and, for API types, to apiSerializersModule.
 */
class ServerJsonTest {
    /** Types exchanged through the server's API content negotiation. */
    private val apiClasses =
        listOf(
            LoginRequest::class,
            LoginResponse::class,
            UserResponse::class,
            ErrorResponse::class,
            CompositeErrorWithDetails::class,
            AccountSettingsView::class,
            UserSettingsPatch::class,
            FileAltTextPatch::class,
            FileUploadResponse::class,
            GenerateAltTextRequest::class,
            GenerateAltTextResponse::class,
            NewPostRequest::class,
            NewPostResponse::class,
            Post::class,
            TwitterStatusResponse::class,
            LinkedInStatusResponse::class,
        )

    /** Types serialized/deserialized reflectively by the platform clients. */
    private val clientTypes =
        listOf(
            typeOf<BlueskyBlobUploadResponse>(),
            typeOf<BlueskyCreateRecordRequest>(),
            typeOf<BlueskyCreateSessionRequest>(),
            typeOf<BlueskyPostResponse>(),
            typeOf<BlueskySessionResponse>(),
            typeOf<LinkedInRegisterUploadRequest>(),
            typeOf<LinkedInRegisterUploadResponse>(),
            typeOf<LinkedInTokenResponse>(),
            typeOf<LinkedInUserProfile>(),
            typeOf<UgcPostRequest>(),
            typeOf<OpenAiChatRequest>(),
            typeOf<OpenAiChatResponse>(),
            typeOf<MastodonMediaResponse>(),
            typeOf<MastodonStatusResponse>(),
            typeOf<TwitterMediaResponse>(),
            typeOf<TwitterPostResponse>(),
        )

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `API serializers are registered in serverJson's module`() {
        val module = serverJson().serializersModule
        apiClasses.forEach { kClass ->
            val serializer = module.getContextual(kClass)
            if (serializer == null) {
                fail(
                    "serverJson's module has no serializer for ${kClass.java.name}"
                )
            }
        }
    }

    @Test
    fun `client serializers resolve through runtime lookup`() {
        clientTypes.forEach { type ->
            assertResolves(type) { serializer(type) }
        }
    }

    /**
     * CompositeErrorResponse embeds NewPostResponse values, and
     * JsonContentPolymorphicSerializer.serialize resolves the concrete
     * subtype's serializer at runtime — module polymorphic registrations first,
     * reflection as fallback, which native images can't do for
     * NewLinkedInPostResponse/NewTwitterPostResponse.
     */
    @Test
    fun `every post-response subtype serializes through the polymorphic serializer`() {
        val json = serverJson()
        val responses =
            listOf(
                NewBlueSkyPostResponse(uri = "at://did/plc/r/1"),
                NewMastodonPostResponse(uri = "https://mastodon.social/s/1"),
                NewFeedPostResponse(uri = "/feed/post/1"),
                NewTwitterPostResponse(id = "170123"),
                NewLinkedInPostResponse(postId = "urn:li:share:1"),
            )
        responses.forEach { response ->
            val encoded =
                json.encodeToString(NewPostResponseSerializer, response)
            val decoded =
                json.decodeFromString(NewPostResponseSerializer, encoded)
            assertEquals(response.javaClass.name, decoded.javaClass.name)
            assertEquals(response.module, decoded.module)
        }
    }

    private fun assertResolves(type: KType, lookup: () -> KSerializer<*>) {
        val serializer =
            try {
                lookup()
            } catch (e: SerializationException) {
                fail("No serializer for $type: ${e.message}", e)
            }
        assertTrue(
            serializer.descriptor.serialName.isNotBlank(),
            "serializer for $type",
        )
    }
}
