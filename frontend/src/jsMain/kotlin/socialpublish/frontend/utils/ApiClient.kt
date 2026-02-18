package socialpublish.frontend.utils

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import org.w3c.files.File

object ApiClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun createHeaders(includeContentType: Boolean = true): dynamic {
        val headers = js("{}")
        if (includeContentType) {
            headers["Content-Type"] = "application/json"
        }
        Storage.getJwtToken()?.let { headers["Authorization"] = "Bearer $it" }
        return headers
    }

    suspend inline fun <reified T, reified B> post(url: String, body: B? = null): ApiResponse<T> {
        return try {
            val requestInit =
                RequestInit(
                    method = "POST",
                    headers = createHeaders(),
                    body = body?.let { json.encodeToString(it) },
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
                    console.warn("Failed to decode error response from $url:", e)
                    ApiResponse.Error("HTTP ${response.status} error", response.status.toInt())
                }
            }
        } catch (e: Throwable) {
            console.error("ApiClient.post exception:", e)
            ApiResponse.Exception(e.message ?: "Unknown error")
        }
    }

    suspend inline fun <reified T, reified B> put(url: String, body: B? = null): ApiResponse<T> {
        return try {
            val requestInit =
                RequestInit(
                    method = "PUT",
                    headers = createHeaders(),
                    body = body?.let { json.encodeToString(it) },
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
                    console.warn("Failed to decode error response from $url:", e)
                    ApiResponse.Error("HTTP ${response.status} error", response.status.toInt())
                }
            }
        } catch (e: Throwable) {
            console.error("ApiClient.put exception:", e)
            ApiResponse.Exception(e.message ?: "Unknown error")
        }
    }

    suspend inline fun <reified T, reified B> patch(url: String, body: B? = null): ApiResponse<T> {
        return try {
            val requestInit =
                RequestInit(
                    method = "PATCH",
                    headers = createHeaders(),
                    body = body?.let { json.encodeToString(it) },
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
                    console.warn("Failed to decode error response from $url:", e)
                    ApiResponse.Error("HTTP ${response.status} error", response.status.toInt())
                }
            }
        } catch (e: Throwable) {
            console.error("ApiClient.patch exception:", e)
            ApiResponse.Exception(e.message ?: "Unknown error")
        }
    }

    suspend inline fun <reified T> get(url: String): ApiResponse<T> {
        return try {
            val requestInit = RequestInit(method = "GET", headers = createHeaders())

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
                    console.warn("Failed to decode error response from $url:", e)
                    ApiResponse.Error("HTTP ${response.status} error", response.status.toInt())
                }
            }
        } catch (e: Throwable) {
            console.error("ApiClient.get exception:", e)
            ApiResponse.Exception(e.message ?: "Unknown error")
        }
    }

    suspend inline fun <reified T> uploadFile(
        url: String,
        file: File,
        altText: String? = null,
    ): ApiResponse<T> {
        return try {
            val formData = org.w3c.xhr.FormData()
            formData.append("file", file)
            if (altText != null) {
                formData.append("altText", altText)
            }

            // Don't include Content-Type header for multipart/form-data
            // Browser will set it automatically with boundary
            val headers = js("{}")
            Storage.getJwtToken()?.let { headers["Authorization"] = "Bearer $it" }

            val requestInit = RequestInit(method = "POST", headers = headers, body = formData)

            val response: Response = window.fetch(url, requestInit).await()
            val text = response.text().await()

            if (response.ok) {
                val data = json.decodeFromString<T>(text)
                ApiResponse.Success(data)
            } else {
                ApiResponse.Error("HTTP ${response.status}: $text", response.status.toInt())
            }
        } catch (e: Throwable) {
            console.error("ApiClient.uploadFile exception:", e)
            ApiResponse.Exception(e.message ?: "Unknown error")
        }
    }
}

@Serializable data class ErrorResponse(val error: String)

sealed class ApiResponse<out T> {
    data class Success<T>(val data: T) : ApiResponse<T>()

    data class Error(val message: String, val code: Int) : ApiResponse<Nothing>()

    data class Exception(val message: String) : ApiResponse<Nothing>()
}
