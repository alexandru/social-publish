package socialpublish.backend.utils

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Operation
import io.ktor.openapi.Responses
import io.ktor.openapi.SecuritySchemeIn
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.routing.openapi.registerApiKeySecurityScheme
import io.ktor.server.routing.openapi.registerBearerAuthSecurityScheme
import socialpublish.backend.models.ErrorResponse

fun Operation.Builder.documentSecurityRequirements() {
    security {
        // Each call = an OR alternative
        requirement("bearerAuth")
        requirement("accessTokenQuery")
        requirement("accessTokenCookie")
    }
}

fun Application.configureOpenApiSecuritySchemes() {
    // 1) Authorization: Bearer <JWT>
    registerBearerAuthSecurityScheme(
        name = "bearerAuth",
        description = "JWT via Authorization: Bearer <token>",
        bearerFormat = "JWT",
    )

    // 2) access_token in query
    registerApiKeySecurityScheme(
        name = "accessTokenQuery",
        keyName = "access_token",
        keyLocation = SecuritySchemeIn.QUERY,
        description = "JWT via ?access_token=... (discouraged, but supported)",
    )

    // 3) access_token in cookie
    registerApiKeySecurityScheme(
        name = "accessTokenCookie",
        keyName = "access_token",
        keyLocation = SecuritySchemeIn.COOKIE,
        description = "JWT via Cookie: access_token=...",
    )
}

inline fun <reified T : Any> Responses.Builder.documentNewPostResponses() {
    HttpStatusCode.OK {
        description = "Post created successfully"
        schema = jsonSchema<T>()
    }
    HttpStatusCode.Unauthorized {
        description = "Not authenticated"
        schema = jsonSchema<ErrorResponse>()
    }
    HttpStatusCode.BadRequest {
        description = "Invalid post data"
        schema = jsonSchema<ErrorResponse>()
    }
    HttpStatusCode.InternalServerError {
        description = "Server error while creating post"
        schema = jsonSchema<ErrorResponse>()
    }
    HttpStatusCode.ServiceUnavailable {
        description = "Possible misconfiguration (e.g., service not set up)"
        schema = jsonSchema<ErrorResponse>()
    }
}

fun Responses.Builder.documentOAuthCallbackResponses() {
    HttpStatusCode.Found {
        description = "Successful authorization, redirects to account page (302 Found)"
    }
    HttpStatusCode.BadRequest {
        description = "Missing required parameters (code, state, or verifier)"
        schema = jsonSchema<ErrorResponse>()
    }
    HttpStatusCode.Unauthorized {
        description = "State parameter mismatch or authorization failed"
        schema = jsonSchema<ErrorResponse>()
    }
    HttpStatusCode.InternalServerError {
        description = "Failed to save authorization token"
        schema = jsonSchema<ErrorResponse>()
    }
}

fun Responses.Builder.documentOAuthStatusResponses() {
    HttpStatusCode.OK { description = "Authorization status retrieved successfully" }
    HttpStatusCode.InternalServerError {
        description = "Failed to check authorization status"
        schema = jsonSchema<ErrorResponse>()
    }
}

fun Operation.Builder.documentOAuthAuthorizeSpec(oauthVersion: String, platform: String) {
    description =
        "Starts the $oauthVersion authorization flow for $platform. Redirects to $platform's authorization page."
    documentSecurityRequirements()
    responses {
        HttpStatusCode.Found { description = "Redirect to $platform authorization URL (302 Found)" }
        HttpStatusCode.Unauthorized {
            description = "Not authenticated (missing or invalid JWT token)"
            schema = jsonSchema<ErrorResponse>()
        }
        HttpStatusCode.ServiceUnavailable {
            description = "$platform integration not configured"
            schema = jsonSchema<ErrorResponse>()
        }
        HttpStatusCode.InternalServerError {
            description = "Failed to generate authorization URL"
            schema = jsonSchema<ErrorResponse>()
        }
    }
}

fun Operation.Builder.documentTwitterCallbackSpec() {
    description =
        "Handles the OAuth 1.0a callback from Twitter after user authorization. Exchanges the oauth_token and oauth_verifier for access credentials."
    parameters {
        query("oauth_token") {
            required = true
            description = "OAuth token provided by Twitter"
        }
        query("oauth_verifier") {
            required = true
            description = "OAuth verifier (PIN) provided by Twitter"
        }
    }
    responses { documentOAuthCallbackResponses() }
}

fun Operation.Builder.documentLinkedInCallbackSpec() {
    description =
        "Handles the OAuth 2.0 callback from LinkedIn after user authorization. Exchanges the authorization code for access credentials."
    parameters {
        query("code") {
            required = true
            description = "Authorization code from LinkedIn"
        }
        query("state") {
            required = true
            description = "State parameter for CSRF protection"
        }
        query("error") {
            required = false
            description = "Error code if authorization failed"
        }
        query("error_description") {
            required = false
            description = "Error description"
        }
    }
    responses { documentOAuthCallbackResponses() }
}
