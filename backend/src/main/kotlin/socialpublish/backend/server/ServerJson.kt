package socialpublish.backend.server

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import socialpublish.backend.models.CompositeErrorResponse
import socialpublish.backend.models.CompositeErrorWithDetails
import socialpublish.backend.models.NewBlueSkyPostResponse
import socialpublish.backend.models.NewLinkedInPostResponse
import socialpublish.backend.models.NewMastodonPostResponse
import socialpublish.backend.models.NewPostResponse
import socialpublish.backend.models.NewRssPostResponse
import socialpublish.backend.models.NewTwitterPostResponse

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
            subclass(NewRssPostResponse::class)
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
