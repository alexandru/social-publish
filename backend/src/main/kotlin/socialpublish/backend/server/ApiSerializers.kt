package socialpublish.backend.server

import kotlinx.serialization.modules.SerializersModule
import socialpublish.backend.clients.llm.GenerateAltTextRequest
import socialpublish.backend.clients.llm.GenerateAltTextResponse
import socialpublish.backend.common.CompositeErrorWithDetails
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.NewPostResponseSerializer
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
 * Serializers for every type exchanged through the server's JSON API.
 *
 * Ktor's content negotiation resolves serializers at runtime, falling back to
 * reflection, which native images cannot do without GraalVM metadata.
 * Registering the serializers here makes that lookup a static module hit
 * instead — no reflection metadata needed for API types.
 *
 * ServerJsonTest fails under nativeTest for any API type missing here, so add
 * new request/response DTOs to this module.
 */
val apiSerializersModule: SerializersModule = SerializersModule {
    contextual(LoginRequest::class, LoginRequest.serializer())
    contextual(LoginResponse::class, LoginResponse.serializer())
    contextual(UserResponse::class, UserResponse.serializer())
    contextual(ErrorResponse::class, ErrorResponse.serializer())
    contextual(
        CompositeErrorWithDetails::class,
        CompositeErrorWithDetails.serializer(),
    )
    contextual(AccountSettingsView::class, AccountSettingsView.serializer())
    contextual(UserSettingsPatch::class, UserSettingsPatch.serializer())
    contextual(FileAltTextPatch::class, FileAltTextPatch.serializer())
    contextual(FileUploadResponse::class, FileUploadResponse.serializer())
    contextual(
        GenerateAltTextRequest::class,
        GenerateAltTextRequest.serializer(),
    )
    contextual(
        GenerateAltTextResponse::class,
        GenerateAltTextResponse.serializer(),
    )
    contextual(NewPostRequest::class, NewPostRequest.serializer())
    contextual(NewPostResponse::class, NewPostResponseSerializer)
    contextual(Post::class, Post.serializer())
    contextual(TwitterStatusResponse::class, TwitterStatusResponse.serializer())
    contextual(
        LinkedInStatusResponse::class,
        LinkedInStatusResponse.serializer(),
    )
}
