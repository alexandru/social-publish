package socialpublish.backend.integrations.linkedin

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import socialpublish.backend.models.ApiResult
import socialpublish.backend.models.CaughtException
import socialpublish.backend.models.RequestError
import socialpublish.backend.models.ResponseBody
import socialpublish.backend.models.ValidationError

private val logger = KotlinLogging.logger {}

@Serializable
data class LinkedinAuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

class LinkedinAuthModule(
    private val config: LinkedinConfig,
    private val apiModule: LinkedinApiModule,
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    companion object {
        fun defaultHttpClient(): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
    }

    suspend fun authorize(authCode: String, redirectUri: String): ApiResult<Unit> {
        return try {
            val response =
                httpClient.post("https://www.linkedin.com/oauth/v2/accessToken") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                append("grant_type", "authorization_code")
                                append("code", authCode)
                                append("redirect_uri", redirectUri)
                                append("client_id", config.clientId)
                                append("client_secret", config.clientSecret)
                            }
                        )
                    )
                }

            if (response.status != HttpStatusCode.OK) {
                val body = response.bodyAsText()
                return RequestError(
                        status = response.status.value,
                        module = "linkedin",
                        errorMessage = "Failed to exchange LinkedIn auth code",
                        body = ResponseBody(asString = body),
                    )
                    .left()
            }

            val token = response.body<LinkedinAuthResponse>()
            apiModule
                .saveToken(LinkedinToken(token.accessToken, token.expiresIn))
                .mapLeft { it }
        } catch (e: Exception) {
            logger.error(e) { "Failed LinkedIn OAuth" }
            CaughtException(
                    status = 500,
                    module = "linkedin",
                    errorMessage = "LinkedIn authorization failed: ${e.message}",
                )
                .left()
        }
    }

    suspend fun status(): ApiResult<Boolean> = apiModule.hasLinkedinAuth().right()
}
