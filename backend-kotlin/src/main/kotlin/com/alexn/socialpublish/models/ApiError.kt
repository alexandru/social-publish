package com.alexn.socialpublish.models

import arrow.core.Either
import kotlinx.serialization.Serializable

/**
 * Represents typed errors in the application using Arrow's Either type.
 */
sealed class ApiError {
    abstract val status: Int
    abstract val module: String?
    abstract val errorMessage: String
}

@Serializable
data class ValidationError(
    override val status: Int,
    override val errorMessage: String,
    override val module: String? = null
) : ApiError()

@Serializable
data class RequestError(
    override val status: Int,
    override val module: String? = null,
    override val errorMessage: String = "API request error",
    val body: ResponseBody? = null
) : ApiError()

@Serializable
data class ResponseBody(
    val asString: String,
    val asJson: String? = null
)

@Serializable
data class CaughtException(
    override val status: Int = 500,
    override val module: String? = null,
    override val errorMessage: String = "Internal server error"
) : ApiError()

@Serializable
data class CompositeError(
    override val status: Int = 400,
    override val module: String? = null,
    override val errorMessage: String = "Multiple API requests failed"
) : ApiError()

/**
 * Type alias for common Result type pattern
 */
typealias ApiResult<T> = Either<ApiError, T>
