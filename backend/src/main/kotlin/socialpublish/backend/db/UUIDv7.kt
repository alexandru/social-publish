package socialpublish.backend.db

import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid as KotlinUuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@JvmInline
@Serializable(with = UUIDv7Serializer::class)
value class UUIDv7(val value: UUID) {
    override fun toString() = value.toString()

    companion object {
        fun fromString(value: String): UUIDv7 = UUIDv7(UUID.fromString(value))

        @OptIn(ExperimentalUuidApi::class)
        fun generate(): UUIDv7 = UUIDv7(UUID.fromString(KotlinUuid.generateV7().toString()))
    }
}

object UUIDv7Serializer : KSerializer<UUIDv7> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UUIDv7", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUIDv7) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUIDv7 {
        return UUIDv7.fromString(decoder.decodeString())
    }
}
