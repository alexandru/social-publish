package socialpublish.backend.common

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Represents a field in a PATCH request body with three distinct states:
 * - [Undefined] — the field was absent from the JSON; keep the existing value unchanged.
 * - [Some]`(null)` — the field was explicitly `null`; clear/remove the value.
 * - [Some]`(value)` — the field was present with a value; update to the new value.
 *
 * Use `encodeDefaults = false` in the JSON configuration so that [Undefined] fields are omitted
 * when serialising outbound payloads.
 */
@Serializable(with = PatchedSerializer::class)
sealed interface Patched<out T> {
    @Serializable data class Some<out T>(val value: T?) : Patched<T>

    @Serializable data object Undefined : Patched<Nothing>
}

/**
 * Custom serializer for [Patched].
 *
 * On the wire [Patched] looks exactly like `T?`:
 * - A present key with a value deserialises to [Patched.Some]`(value)`.
 * - A present key with `null` deserialises to [Patched.Some]`(null)`.
 * - An absent key uses the property's default value ([Patched.Undefined]) — the serializer itself
 *   is never called for absent keys, which is standard kotlinx.serialization behaviour.
 */
class PatchedSerializer<T : Any>(private val tSerializer: KSerializer<T>) :
    KSerializer<Patched<T>> {

    private val nullableT: KSerializer<T?> = tSerializer.nullable

    // On the wire we look exactly like T? so the descriptor is identical.
    override val descriptor: SerialDescriptor = nullableT.descriptor

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Patched<T>) {
        when (value) {
            is Patched.Some -> encoder.encodeSerializableValue(nullableT, value.value)
            Patched.Undefined -> encoder.encodeNull()
        }
    }

    override fun deserialize(decoder: Decoder): Patched<T> {
        // This is only called when the key IS present in the JSON.
        // An absent key means the property keeps its default value (Patched.Undefined).
        val v: T? = decoder.decodeSerializableValue(nullableT)
        return Patched.Some(v)
    }
}
