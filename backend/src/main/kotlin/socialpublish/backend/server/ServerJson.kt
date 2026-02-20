package socialpublish.backend.server

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import socialpublish.backend.common.CompositeErrorResponse
import socialpublish.backend.common.CompositeErrorWithDetails
import socialpublish.backend.common.NewBlueSkyPostResponse
import socialpublish.backend.common.NewFeedPostResponse
import socialpublish.backend.common.NewLinkedInPostResponse
import socialpublish.backend.common.NewMastodonPostResponse
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.NewTwitterPostResponse

@OptIn(ExperimentalSerializationApi::class)
fun serverJson(): Json = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    // Disable class discriminator to avoid adding "type" field
    classDiscriminator = "#type"
    classDiscriminatorMode = ClassDiscriminatorMode.NONE
    serializersModule = SerializersModule {
        polymorphic(NewPostResponse::class) {
            subclass(NewFeedPostResponse::class)
            subclass(NewMastodonPostResponse::class)
            subclass(NewBlueSkyPostResponse::class)
            subclass(NewTwitterPostResponse::class)
            subclass(NewLinkedInPostResponse::class)
        }
        // Explicitly register serializers
        contextual(CompositeErrorResponse::class, CompositeErrorResponse.serializer())
        contextual(CompositeErrorWithDetails::class, CompositeErrorWithDetails.serializer())
    }
}
