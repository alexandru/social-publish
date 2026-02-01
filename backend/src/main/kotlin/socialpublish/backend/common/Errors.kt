package socialpublish.backend.common

sealed interface DomainError {
    val message: String
    val cause: Throwable?
}

sealed class AuthError(override val message: String, override val cause: Throwable? = null) :
    DomainError {
    data class InvalidToken(val details: String, override val cause: Throwable? = null) :
        AuthError("Invalid JWT token: $details", cause)

    data class InvalidCredentials(val details: String = "Invalid username or password") :
        AuthError(details)

    data class PasswordVerificationFailed(override val cause: Throwable) :
        AuthError("Password verification failed", cause)
}

sealed class FileError(override val message: String, override val cause: Throwable? = null) :
    DomainError {
    data class InvalidFormat(val format: String, val expected: List<String>) :
        FileError("Invalid file format: $format. Expected one of: ${expected.joinToString()}")

    data class ProcessingFailed(val details: String, override val cause: Throwable? = null) :
        FileError("File processing failed: $details", cause)

    data class ReadFailed(val path: String, override val cause: Throwable) :
        FileError("Failed to read file: $path", cause)

    data class WriteFailed(val path: String, override val cause: Throwable) :
        FileError("Failed to write file: $path", cause)
}

sealed class ParseError(override val message: String, override val cause: Throwable? = null) :
    DomainError {
    data class HtmlParsingFailed(val url: String, override val cause: Throwable? = null) :
        ParseError("Failed to parse HTML from: $url", cause)

    data class UrlParsingFailed(val url: String, override val cause: Throwable? = null) :
        ParseError("Failed to parse URL: $url", cause)

    data class MissingRequiredField(val field: String, val url: String) :
        ParseError("Missing required field '$field' for: $url")

    data class FetchFailed(
        val url: String,
        val statusCode: Int? = null,
        override val cause: Throwable? = null,
    ) :
        ParseError(
            "Failed to fetch URL: $url" + (statusCode?.let { " (status: $it)" } ?: ""),
            cause,
        )
}
