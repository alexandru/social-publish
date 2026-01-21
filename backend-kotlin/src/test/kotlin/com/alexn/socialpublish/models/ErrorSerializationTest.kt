package com.alexn.socialpublish.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests to ensure error JSON serialization matches the legacy backend format.
 */
class ErrorSerializationTest {
    
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    @Test
    fun `ValidationError should serialize correctly`() {
        val error = ValidationError(
            status = 400,
            errorMessage = "Content must be between 1 and 1000 characters",
            module = "form"
        )
        
        val jsonString = json.encodeToString(error)
        
        assert(jsonString.contains("\"status\":400"))
        assert(jsonString.contains("\"errorMessage\":\"Content must be between 1 and 1000 characters\""))
        assert(jsonString.contains("\"module\":\"form\""))
    }

    @Test
    fun `RequestError should serialize correctly`() {
        val error = RequestError(
            status = 503,
            module = "mastodon",
            errorMessage = "API request error",
            body = ResponseBody(
                asString = "{\"error\":\"Service unavailable\"}",
                asJson = "{\"error\":\"Service unavailable\"}"
            )
        )
        
        val jsonString = json.encodeToString(error)
        
        assert(jsonString.contains("\"status\":503"))
        assert(jsonString.contains("\"module\":\"mastodon\""))
        assert(jsonString.contains("\"errorMessage\":\"API request error\""))
        assert(jsonString.contains("\"body\""))
        assert(jsonString.contains("\"asString\""))
    }

    @Test
    fun `CaughtException should serialize correctly`() {
        val error = CaughtException(
            status = 500,
            module = "twitter",
            errorMessage = "Internal server error"
        )
        
        val jsonString = json.encodeToString(error)
        
        assert(jsonString.contains("\"status\":500"))
        assert(jsonString.contains("\"module\":\"twitter\""))
        assert(jsonString.contains("\"errorMessage\":\"Internal server error\""))
    }

    @Test
    fun `CompositeErrorResponse with success should serialize correctly`() {
        val response = CompositeErrorResponse(
            type = "success",
            result = NewRssPostResponse(uri = "http://localhost:3000/rss/123")
        )
        
        val jsonString = json.encodeToString(response)
        
        assert(jsonString.contains("\"type\":\"success\""))
        assert(jsonString.contains("\"result\""))
        assert(jsonString.contains("\"uri\":\"http://localhost:3000/rss/123\""))
    }

    @Test
    fun `CompositeErrorResponse with error should serialize correctly`() {
        val response = CompositeErrorResponse(
            type = "error",
            module = "mastodon",
            status = 503,
            error = "Mastodon integration not configured"
        )
        
        val jsonString = json.encodeToString(response)
        
        assert(jsonString.contains("\"type\":\"error\""))
        assert(jsonString.contains("\"module\":\"mastodon\""))
        assert(jsonString.contains("\"status\":503"))
        assert(jsonString.contains("\"error\":\"Mastodon integration not configured\""))
    }

    @Test
    fun `CompositeError should serialize correctly`() {
        val error = CompositeError(
            status = 503,
            module = "form",
            errorMessage = "Failed to create post via mastodon.",
            responses = listOf(
                CompositeErrorResponse(
                    type = "success",
                    result = NewRssPostResponse(uri = "http://localhost:3000/rss/123")
                ),
                CompositeErrorResponse(
                    type = "error",
                    module = "mastodon",
                    status = 503,
                    error = "Mastodon integration not configured"
                )
            )
        )
        
        val jsonString = json.encodeToString(error)
        
        assert(jsonString.contains("\"status\":503"))
        assert(jsonString.contains("\"module\":\"form\""))
        assert(jsonString.contains("\"errorMessage\":\"Failed to create post via mastodon.\""))
        assert(jsonString.contains("\"responses\""))
        assert(jsonString.contains("\"type\":\"success\""))
        assert(jsonString.contains("\"type\":\"error\""))
    }
}
