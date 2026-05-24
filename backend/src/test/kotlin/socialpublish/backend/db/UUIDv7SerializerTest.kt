package socialpublish.backend.db

import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class UUIDv7SerializerTest {
    @Serializable private data class TestPayload(val uuid: UUIDv7)

    @Test
    fun `serializes UUIDv7 as string`() {
        val uuid = UUIDv7.fromString("018f7c6f-9c2d-7a4e-8d6b-0d673c47c6aa")

        val json = Json.encodeToString(TestPayload(uuid))

        assertEquals("""{"uuid":"018f7c6f-9c2d-7a4e-8d6b-0d673c47c6aa"}""", json)
    }

    @Test
    fun `deserializes UUIDv7 from string`() {
        val decoded =
            Json.decodeFromString<TestPayload>(
                """{"uuid":"018f7c6f-9c2d-7a4e-8d6b-0d673c47c6aa"}"""
            )

        assertEquals(UUIDv7.fromString("018f7c6f-9c2d-7a4e-8d6b-0d673c47c6aa"), decoded.uuid)
    }

    @Test
    fun `generate returns UUIDv7`() {
        val uuid = UUIDv7.generate()
        assertEquals(7, uuid.value.version())
    }
}
