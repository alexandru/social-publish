package socialpublish.frontend.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import socialpublish.frontend.pages.TwitterStatusResponse

class SerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testLoginRequestSerializationRoundTrip() {
        val original = LoginRequest(username = "testuser", password = "testpass")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<LoginRequest>(encoded)

        assertEquals(original, decoded)
        assertEquals("testuser", decoded.username)
        assertEquals("testpass", decoded.password)
    }

    @Test
    fun testLoginRequestDeserialization() {
        val jsonString = """{"username":"alice","password":"secret123"}"""
        val decoded = json.decodeFromString<LoginRequest>(jsonString)

        assertEquals("alice", decoded.username)
        assertEquals("secret123", decoded.password)
    }

    @Test
    fun testLoginResponseSerializationRoundTrip() {
        val original =
            LoginResponse(
                token = "jwt-token-123",
                configuredServices = ConfiguredServices(twitter = true),
            )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<LoginResponse>(encoded)

        assertEquals(original, decoded)
        assertEquals("jwt-token-123", decoded.token)
        assertTrue(decoded.configuredServices.twitter)
    }

    @Test
    fun testLoginResponseDeserialization() {
        val jsonString = """{"token":"abc123","configuredServices":{"twitter":true}}"""
        val decoded = json.decodeFromString<LoginResponse>(jsonString)

        assertEquals("abc123", decoded.token)
        assertTrue(decoded.configuredServices.twitter)
    }

    @Test
    fun testConfiguredServicesSerializationRoundTrip() {
        val original = ConfiguredServices(twitter = true)
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ConfiguredServices>(encoded)

        assertEquals(original, decoded)
        assertTrue(decoded.twitter)
    }

    @Test
    fun testConfiguredServicesDeserializationWithDefaults() {
        val jsonString = """{}"""
        val decoded = json.decodeFromString<ConfiguredServices>(jsonString)

        assertEquals(ConfiguredServices(), decoded)
        assertEquals(false, decoded.twitter)
    }

    @Test
    fun testConfiguredServicesDeserializationWithTwitterEnabled() {
        val jsonString = """{"twitter":true}"""
        val decoded = json.decodeFromString<ConfiguredServices>(jsonString)

        assertTrue(decoded.twitter)
    }

    @Test
    fun testFileUploadResponseSerializationRoundTrip() {
        val original = FileUploadResponse(uuid = "file-uuid-456")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<FileUploadResponse>(encoded)

        assertEquals(original, decoded)
        assertEquals("file-uuid-456", decoded.uuid)
    }

    @Test
    fun testFileUploadResponseDeserialization() {
        val jsonString = """{"uuid":"uuid-789"}"""
        val decoded = json.decodeFromString<FileUploadResponse>(jsonString)

        assertEquals("uuid-789", decoded.uuid)
    }

    @Test
    fun testPublishRequestSerializationRoundTrip() {
        val original =
            PublishRequest(
                targets = listOf("twitter", "mastodon"),
                language = "en",
                messages =
                    listOf(
                        PublishRequestMessage(
                            content = "Test post content",
                            link = "https://example.com",
                            images = listOf("img1.jpg", "img2.jpg"),
                        )
                    ),
            )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PublishRequest>(encoded)

        assertEquals(original, decoded)
        assertEquals(listOf("twitter", "mastodon"), decoded.targets)
        assertEquals("Test post content", decoded.messages.first().content)
        assertEquals("https://example.com", decoded.messages.first().link)
        assertEquals(listOf("img1.jpg", "img2.jpg"), decoded.messages.first().images)
        assertEquals("en", decoded.language)
    }

    @Test
    fun testPublishRequestDeserializationWithDefaults() {
        val jsonString = """{"targets":["twitter"],"messages":[{"content":"Hello"}]}"""
        val decoded = json.decodeFromString<PublishRequest>(jsonString)

        assertEquals(listOf("twitter"), decoded.targets)
        assertEquals("Hello", decoded.messages.first().content)
        assertEquals(null, decoded.messages.first().link)
        assertEquals(null, decoded.messages.first().images)
        assertEquals(null, decoded.language)
    }

    @Test
    fun testPublishRequestDeserializationComplete() {
        val jsonString =
            """{"targets":["mastodon"],"messages":[{"content":"Post","link":"https://test.com","images":["a.png"]}],"language":"ro"}"""
        val decoded = json.decodeFromString<PublishRequest>(jsonString)

        assertEquals("Post", decoded.messages.first().content)
        assertEquals("https://test.com", decoded.messages.first().link)
        assertEquals(listOf("mastodon"), decoded.targets)
        assertEquals(listOf("a.png"), decoded.messages.first().images)
        assertEquals("ro", decoded.language)
    }

    @Test
    fun testModulePostResponseSerializationRoundTrip() {
        val original =
            ModulePostResponse(
                module = "twitter",
                uri = "https://twitter.com/post/123",
                id = "123",
                cid = "cid-456",
            )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ModulePostResponse>(encoded)

        assertEquals(original, decoded)
        assertEquals("twitter", decoded.module)
        assertEquals("https://twitter.com/post/123", decoded.uri)
        assertEquals("123", decoded.id)
        assertEquals("cid-456", decoded.cid)
    }

    @Test
    fun testModulePostResponseDeserializationMinimal() {
        val jsonString = """{"module":"mastodon"}"""
        val decoded = json.decodeFromString<ModulePostResponse>(jsonString)

        assertEquals("mastodon", decoded.module)
        assertEquals(null, decoded.uri)
        assertEquals(null, decoded.id)
        assertEquals(null, decoded.cid)
    }

    @Test
    fun testModulePostResponseDeserializationComplete() {
        val jsonString =
            """{"module":"bluesky","uri":"at://did/post","id":"postid","cid":"cidvalue"}"""
        val decoded = json.decodeFromString<ModulePostResponse>(jsonString)

        assertEquals("bluesky", decoded.module)
        assertEquals("at://did/post", decoded.uri)
        assertEquals("postid", decoded.id)
        assertEquals("cidvalue", decoded.cid)
    }

    @Test
    fun testModulePostResponseWithPartialData() {
        val jsonString = """{"module":"feed","uri":"https://blog.com/post"}"""
        val decoded = json.decodeFromString<ModulePostResponse>(jsonString)

        assertEquals("feed", decoded.module)
        assertEquals("https://blog.com/post", decoded.uri)
        assertEquals(null, decoded.id)
        assertEquals(null, decoded.cid)
    }

    @Test
    fun testSerializationPreservesStructure() {
        // Test that serialization output has expected structure
        val request =
            PublishRequest(
                targets = listOf("twitter"),
                messages = listOf(PublishRequestMessage(content = "Test")),
            )
        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"content\":\"Test\""))
        assertTrue(encoded.contains("\"targets\":[\"twitter\"]"))
    }

    @Test
    fun testComplexNestedSerialization() {
        // Test nested object serialization
        val response =
            LoginResponse(token = "token", configuredServices = ConfiguredServices(twitter = true))
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<LoginResponse>(encoded)

        assertNotNull(decoded.configuredServices)
        assertTrue(decoded.configuredServices.twitter)
    }

    @Test
    fun testTwitterStatusResponseDeserializationWithAuthorization() {
        // Backend returns createdAt as Long (epoch milliseconds)
        val jsonString = """{"hasAuthorization":true,"createdAt":1737478801545}"""
        val decoded = json.decodeFromString<TwitterStatusResponse>(jsonString)

        assertTrue(decoded.hasAuthorization)
        assertNotNull(decoded.createdAt)
        assertEquals(1737478801545L, decoded.createdAt)

        // Verify the date can be constructed from the Long
        val date = kotlin.js.Date(decoded.createdAt)
        val dateString = date.toLocaleString()
        // Should not be "Invalid Date"
        assertTrue(!dateString.contains("Invalid"))
    }

    @Test
    fun testTwitterStatusResponseDeserializationWithoutAuthorization() {
        val jsonString = """{"hasAuthorization":false,"createdAt":null}"""
        val decoded = json.decodeFromString<TwitterStatusResponse>(jsonString)

        assertEquals(false, decoded.hasAuthorization)
        assertEquals(null, decoded.createdAt)
    }

    @Test
    fun testTwitterStatusResponseDeserializationWithoutCreatedAt() {
        val jsonString = """{"hasAuthorization":false}"""
        val decoded = json.decodeFromString<TwitterStatusResponse>(jsonString)

        assertEquals(false, decoded.hasAuthorization)
        assertEquals(null, decoded.createdAt)
    }

    @Test
    fun testTwitterStatusResponseSerializationRoundTrip() {
        val original = TwitterStatusResponse(hasAuthorization = true, createdAt = 1737478801545L)
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<TwitterStatusResponse>(encoded)

        assertEquals(original, decoded)
        assertTrue(decoded.hasAuthorization)
        assertEquals(1737478801545L, decoded.createdAt)
    }

    @Test
    fun testGenerateAltTextRequestSerializationWithLanguage() {
        val original =
            GenerateAltTextRequest(
                imageUuid = "uuid-123",
                userContext = "Custom context",
                language = "en",
            )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<GenerateAltTextRequest>(encoded)

        assertEquals(original, decoded)
        assertEquals("uuid-123", decoded.imageUuid)
        assertEquals("Custom context", decoded.userContext)
        assertEquals("en", decoded.language)
    }

    @Test
    fun testGenerateAltTextRequestSerializationWithoutLanguage() {
        val original = GenerateAltTextRequest(imageUuid = "uuid-456")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<GenerateAltTextRequest>(encoded)

        assertEquals(original, decoded)
        assertEquals("uuid-456", decoded.imageUuid)
        assertEquals(null, decoded.userContext)
        assertEquals(null, decoded.language)
    }
}
