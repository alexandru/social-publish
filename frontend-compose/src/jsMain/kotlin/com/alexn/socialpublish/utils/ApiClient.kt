package com.alexn.socialpublish.utils

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response

object ApiClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun createHeaders(includeAuth: Boolean = true): dynamic {
        val headers = js("{}")
        headers["Content-Type"] = "application/json"
        if (includeAuth) {
            Storage.getJwtToken()?.let {
                headers["Authorization"] = "Bearer $it"
            }
        }
        return headers
    }

    suspend inline fun <reified T> post(url: String, body: Any? = null): ApiResponse<T> {
        return try {
            val requestInit = RequestInit(
                method = "POST",
                headers = createHeaders(),
                body = body?.let { json.encodeToString(it) }
            )

            val response: Response = window.fetch(url, requestInit).await()
            val text = response.text().await()

            if (response.ok) {
                val data = json.decodeFromString<T>(text)
                ApiResponse.Success(data)
            } else {
                try {
                    val error = json.decodeFromString<ErrorResponse>(text)
                    ApiResponse.Error(error.error, response.status.toInt())
                } catch (e: Exception) {
                    ApiResponse.Error("HTTP ${response.status} error", response.status.toInt())
                }
            }
        } catch (e: Exception) {
            ApiResponse.Exception(e.message ?: "Unknown error")
        }
    }

    suspend inline fun <reified T> get(url: String): ApiResponse<T> {
        return try {
            val requestInit = RequestInit(
                method = "GET",
                headers = createHeaders()
            )

            val response: Response = window.fetch(url, requestInit).await()
            val text = response.text().await()

            if (response.ok) {
                val data = json.decodeFromString<T>(text)
                ApiResponse.Success(data)
            } else {
                try {
                    val error = json.decodeFromString<ErrorResponse>(text)
                    ApiResponse.Error(error.error, response.status.toInt())
                } catch (e: Exception) {
                    ApiResponse.Error("HTTP ${response.status} error", response.status.toInt())
                }
            }
        } catch (e: Exception) {
            ApiResponse.Exception(e.message ?: "Unknown error")
        }
    }
}

@kotlinx.serialization.Serializable
data class ErrorResponse(
    val error: String
)

sealed class ApiResponse<out T> {
    data class Success<T>(val data: T) : ApiResponse<T>()
    data class Error(val message: String, val code: Int) : ApiResponse<Nothing>()
    data class Exception(val message: String) : ApiResponse<Nothing>()
}
