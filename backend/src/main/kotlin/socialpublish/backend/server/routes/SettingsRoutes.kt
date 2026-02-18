package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import socialpublish.backend.clients.bluesky.BlueskyConfig
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.clients.llm.LlmConfig
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.db.UserSettings
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.server.respondWithInternalServerError
import socialpublish.backend.server.respondWithNotFound

/**
 * Sentinel returned in GET responses for sensitive fields that have a stored value.
 *
 * This is only used in the response body — it is never expected or processed in PATCH requests.
 * PATCH uses standard JSON Merge Patch (RFC 7396): absent field = keep existing, null = remove.
 */
const val MASKED_VALUE = "****"

/**
 * Settings view returned by GET and PATCH responses.
 *
 * Non-sensitive fields (URLs, usernames, IDs) contain their real stored values. Sensitive fields
 * (passwords, tokens, keys, secrets) contain [MASKED_VALUE] when a value is stored, so the
 * frontend knows a value exists without receiving the actual credential.
 */
@Serializable
data class AccountSettingsView(
    val bluesky: BlueskySettingsView? = null,
    val mastodon: MastodonSettingsView? = null,
    val twitter: TwitterSettingsView? = null,
    val linkedin: LinkedInSettingsView? = null,
    val llm: LlmSettingsView? = null,
)

@Serializable
data class BlueskySettingsView(val service: String, val username: String, val password: String)

@Serializable data class MastodonSettingsView(val host: String, val accessToken: String)

@Serializable
data class TwitterSettingsView(val oauth1ConsumerKey: String, val oauth1ConsumerSecret: String)

@Serializable data class LinkedInSettingsView(val clientId: String, val clientSecret: String)

@Serializable data class LlmSettingsView(val apiUrl: String, val apiKey: String, val model: String)

class SettingsRoutes(private val usersDb: UsersDatabase) {
    /**
     * GET /api/account/settings – returns the user's settings.
     *
     * Non-sensitive fields contain real values. Sensitive fields (passwords, tokens, keys) contain
     * [MASKED_VALUE] when a value is stored.
     */
    suspend fun getSettingsRoute(userUuid: UUID, call: ApplicationCall) {
        val user =
            usersDb.findByUuid(userUuid).getOrElse { error ->
                call.respondWithInternalServerError(error, "Failed to retrieve user $userUuid")
                return
            }
                ?: run {
                    call.respondWithNotFound("User")
                    return
                }
        call.respond(user.settings.toView())
    }

    /**
     * PATCH /api/account/settings – partially updates the user's settings via JSON Merge Patch
     * (RFC 7396).
     *
     * Top-level section semantics:
     * - Absent key → section is unchanged
     * - `null` → section is removed
     * - Object → section fields are merged: absent field = keep existing, present field = update
     *
     * Sensitive fields (passwords, tokens, keys) left absent in the request are preserved from the
     * existing stored values.
     */
    suspend fun patchSettingsRoute(userUuid: UUID, call: ApplicationCall) {
        val patch =
            runCatching { call.receive<JsonObject>() }.getOrNull()
                ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "Invalid request body"),
                    )
                    return
                }
        val user =
            usersDb.findByUuid(userUuid).getOrElse { error ->
                call.respondWithInternalServerError(error, "Failed to retrieve user $userUuid")
                return
            }
                ?: run {
                    call.respondWithNotFound("User")
                    return
                }

        val merged = mergeSettingsPatch(existing = user.settings, patch = patch)
        val updated =
            usersDb.updateSettings(userUuid, merged).getOrElse { error ->
                call.respondWithInternalServerError(
                    error,
                    "Failed to update settings for $userUuid",
                )
                return
            }
        if (!updated) {
            call.respondWithNotFound("User")
            return
        }
        call.respond(merged.toView())
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Builds a view of [UserSettings] safe to expose over the API. */
internal fun UserSettings?.toView(): AccountSettingsView =
    AccountSettingsView(
        bluesky =
            this?.bluesky?.let {
                BlueskySettingsView(
                    service = it.service,
                    username = it.username,
                    password = MASKED_VALUE,
                )
            },
        mastodon =
            this?.mastodon?.let {
                MastodonSettingsView(host = it.host, accessToken = MASKED_VALUE)
            },
        twitter =
            this?.twitter?.let {
                TwitterSettingsView(
                    oauth1ConsumerKey = it.oauth1ConsumerKey,
                    oauth1ConsumerSecret = MASKED_VALUE,
                )
            },
        linkedin =
            this?.linkedin?.let {
                LinkedInSettingsView(clientId = it.clientId, clientSecret = MASKED_VALUE)
            },
        llm =
            this?.llm?.let {
                LlmSettingsView(apiUrl = it.apiUrl, apiKey = MASKED_VALUE, model = it.model)
            },
    )

/**
 * Returns the string value of [key] in this [JsonObject] if present and non-blank, the
 * [fallback] if the key is absent, or `null` if the key is present with a blank/null value.
 */
private fun JsonObject.resolveString(key: String, fallback: String?): String? =
    if (key !in this) {
        fallback
    } else {
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

private fun patchBluesky(existing: BlueskyConfig?, patch: JsonObject): BlueskyConfig? {
    val service =
        patch.resolveString("service", existing?.service) ?: "https://bsky.social"
    val username = patch.resolveString("username", existing?.username) ?: return null
    val password = patch.resolveString("password", existing?.password) ?: return null
    return existing?.copy(service = service, username = username, password = password)
        ?: BlueskyConfig(service = service, username = username, password = password)
}

private fun patchMastodon(existing: MastodonConfig?, patch: JsonObject): MastodonConfig? {
    val host = patch.resolveString("host", existing?.host) ?: return null
    val accessToken = patch.resolveString("accessToken", existing?.accessToken) ?: return null
    return MastodonConfig(host = host, accessToken = accessToken)
}

private fun patchTwitter(existing: TwitterConfig?, patch: JsonObject): TwitterConfig? {
    val key = patch.resolveString("oauth1ConsumerKey", existing?.oauth1ConsumerKey) ?: return null
    val secret =
        patch.resolveString("oauth1ConsumerSecret", existing?.oauth1ConsumerSecret) ?: return null
    return existing?.copy(oauth1ConsumerKey = key, oauth1ConsumerSecret = secret)
        ?: TwitterConfig(oauth1ConsumerKey = key, oauth1ConsumerSecret = secret)
}

private fun patchLinkedIn(existing: LinkedInConfig?, patch: JsonObject): LinkedInConfig? {
    val clientId = patch.resolveString("clientId", existing?.clientId) ?: return null
    val clientSecret = patch.resolveString("clientSecret", existing?.clientSecret) ?: return null
    return existing?.copy(clientId = clientId, clientSecret = clientSecret)
        ?: LinkedInConfig(clientId = clientId, clientSecret = clientSecret)
}

private fun patchLlm(existing: LlmConfig?, patch: JsonObject): LlmConfig? {
    val apiUrl = patch.resolveString("apiUrl", existing?.apiUrl) ?: return null
    val apiKey = patch.resolveString("apiKey", existing?.apiKey) ?: return null
    val model = patch.resolveString("model", existing?.model) ?: ""
    return LlmConfig(apiUrl = apiUrl, apiKey = apiKey, model = model)
}

/**
 * Applies a JSON Merge Patch to [existing] settings.
 *
 * - Absent key at the top level → keep existing section
 * - `null` at the top level → remove section
 * - Object at the top level → field-level merge (absent field = keep existing, present = update)
 */
internal fun mergeSettingsPatch(existing: UserSettings?, patch: JsonObject): UserSettings =
    UserSettings(
        bluesky =
            when {
                "bluesky" !in patch -> existing?.bluesky
                patch["bluesky"] is JsonNull -> null
                else -> patchBluesky(existing?.bluesky, patch["bluesky"]!!.jsonObject)
            },
        mastodon =
            when {
                "mastodon" !in patch -> existing?.mastodon
                patch["mastodon"] is JsonNull -> null
                else -> patchMastodon(existing?.mastodon, patch["mastodon"]!!.jsonObject)
            },
        twitter =
            when {
                "twitter" !in patch -> existing?.twitter
                patch["twitter"] is JsonNull -> null
                else -> patchTwitter(existing?.twitter, patch["twitter"]!!.jsonObject)
            },
        linkedin =
            when {
                "linkedin" !in patch -> existing?.linkedin
                patch["linkedin"] is JsonNull -> null
                else -> patchLinkedIn(existing?.linkedin, patch["linkedin"]!!.jsonObject)
            },
        llm =
            when {
                "llm" !in patch -> existing?.llm
                patch["llm"] is JsonNull -> null
                else -> patchLlm(existing?.llm, patch["llm"]!!.jsonObject)
            },
    )

