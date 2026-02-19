package socialpublish.backend.server.routes

import arrow.core.getOrElse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.util.UUID
import kotlinx.serialization.Serializable
import socialpublish.backend.clients.bluesky.BlueskyConfig
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.clients.llm.LlmConfig
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.clients.metathreads.MetaThreadsConfig
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.Patched
import socialpublish.backend.db.UserSettings
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.server.respondWithInternalServerError
import socialpublish.backend.server.respondWithNotFound

/**
 * Sentinel returned in GET responses for sensitive fields that have a stored value.
 *
 * This value is only used in GET/PATCH *responses*. PATCH *requests* use standard JSON Merge Patch
 * semantics via [Patched]: absent field = keep existing, `null` field = remove/clear.
 */
const val MASKED_VALUE = "****"

/**
 * Settings view returned by GET and PATCH responses.
 *
 * Non-sensitive fields (URLs, usernames, IDs) contain their real stored values. Sensitive fields
 * (passwords, tokens, keys, secrets) contain [MASKED_VALUE] when a value is stored, so the frontend
 * knows a value exists without receiving the actual credential.
 */
@Serializable
data class AccountSettingsView(
    val bluesky: BlueskySettingsView? = null,
    val mastodon: MastodonSettingsView? = null,
    val twitter: TwitterSettingsView? = null,
    val linkedin: LinkedInSettingsView? = null,
    val metaThreads: MetaThreadsSettingsView? = null,
    val llm: LlmSettingsView? = null,
)

@Serializable
data class BlueskySettingsView(val service: String, val username: String, val password: String)

@Serializable data class MastodonSettingsView(val host: String, val accessToken: String)

@Serializable
data class TwitterSettingsView(val oauth1ConsumerKey: String, val oauth1ConsumerSecret: String)

@Serializable data class LinkedInSettingsView(val clientId: String, val clientSecret: String)

@Serializable data class MetaThreadsSettingsView(val userId: String, val accessToken: String)

@Serializable data class LlmSettingsView(val apiUrl: String, val apiKey: String, val model: String)

// ---------------------------------------------------------------------------
// PATCH request body DTOs — each field uses Patched<T> so that:
//   absent key  → Patched.Undefined  (keep existing value)
//   null value  → Patched.Some(null) (clear / remove section)
//   present key → Patched.Some(T)    (update to new value)
// ---------------------------------------------------------------------------

@Serializable
data class UserSettingsPatch(
    val bluesky: Patched<BlueskySettingsPatch> = Patched.Undefined,
    val mastodon: Patched<MastodonSettingsPatch> = Patched.Undefined,
    val twitter: Patched<TwitterSettingsPatch> = Patched.Undefined,
    val linkedin: Patched<LinkedInSettingsPatch> = Patched.Undefined,
    val metaThreads: Patched<MetaThreadsSettingsPatch> = Patched.Undefined,
    val llm: Patched<LlmSettingsPatch> = Patched.Undefined,
)

@Serializable
data class BlueskySettingsPatch(
    val service: Patched<String> = Patched.Undefined,
    val username: Patched<String> = Patched.Undefined,
    val password: Patched<String> = Patched.Undefined,
)

@Serializable
data class MastodonSettingsPatch(
    val host: Patched<String> = Patched.Undefined,
    val accessToken: Patched<String> = Patched.Undefined,
)

@Serializable
data class TwitterSettingsPatch(
    val oauth1ConsumerKey: Patched<String> = Patched.Undefined,
    val oauth1ConsumerSecret: Patched<String> = Patched.Undefined,
)

@Serializable
data class LinkedInSettingsPatch(
    val clientId: Patched<String> = Patched.Undefined,
    val clientSecret: Patched<String> = Patched.Undefined,
)

@Serializable
data class MetaThreadsSettingsPatch(
    val userId: Patched<String> = Patched.Undefined,
    val accessToken: Patched<String> = Patched.Undefined,
)

@Serializable
data class LlmSettingsPatch(
    val apiUrl: Patched<String> = Patched.Undefined,
    val apiKey: Patched<String> = Patched.Undefined,
    val model: Patched<String> = Patched.Undefined,
)

// ---------------------------------------------------------------------------
// Route handlers
// ---------------------------------------------------------------------------

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
     * PATCH /api/account/settings – partially updates the user's settings.
     *
     * Uses JSON Merge Patch semantics (RFC 7396) via [Patched]:
     * - Absent key at the top level → section is unchanged.
     * - `null` value at the top level → section is removed.
     * - Object value at the top level → field-level merge within that section.
     *
     * Within a section, absent field → keep existing, present field → update, `null` field → clear
     * (which drops the section when it affects a required field).
     */
    suspend fun patchSettingsRoute(userUuid: UUID, call: ApplicationCall) {
        val patch =
            runCatching { call.receive<UserSettingsPatch>() }.getOrNull()
                ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "Invalid request body"),
                    )
                    return
                }

        if (containsMaskedSecretValue(patch)) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(error = "Masked values are not allowed in PATCH payload"),
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

private fun containsMaskedSecretValue(patch: UserSettingsPatch): Boolean =
    containsMaskedField(patch.bluesky, BlueskySettingsPatch::password) ||
        containsMaskedField(patch.mastodon, MastodonSettingsPatch::accessToken) ||
        containsMaskedField(patch.twitter, TwitterSettingsPatch::oauth1ConsumerSecret) ||
        containsMaskedField(patch.linkedin, LinkedInSettingsPatch::clientSecret) ||
        containsMaskedField(patch.metaThreads, MetaThreadsSettingsPatch::accessToken) ||
        containsMaskedField(patch.llm, LlmSettingsPatch::apiKey)

private fun <T> containsMaskedField(
    patchedSection: Patched<T>,
    secretSelector: (T) -> Patched<String>,
): Boolean =
    when (patchedSection) {
        Patched.Undefined -> false
        is Patched.Some -> {
            val section = patchedSection.value ?: return false
            when (val secret = secretSelector(section)) {
                Patched.Undefined -> false
                is Patched.Some -> secret.value == MASKED_VALUE
            }
        }
    }

// ---------------------------------------------------------------------------
// View helpers
// ---------------------------------------------------------------------------

/** Builds a view of [UserSettings] safe to expose over the API (sensitive fields masked). */
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
        metaThreads =
            this?.metaThreads?.let {
                MetaThreadsSettingsView(userId = it.userId, accessToken = MASKED_VALUE)
            },
        llm =
            this?.llm?.let {
                LlmSettingsView(apiUrl = it.apiUrl, apiKey = MASKED_VALUE, model = it.model)
            },
    )

// ---------------------------------------------------------------------------
// Merge helpers
// ---------------------------------------------------------------------------

/**
 * Resolves a patched string field:
 * - [Patched.Undefined] → keep [existing] value (null if there is none)
 * - [Patched.Some]`(blank/null)` → treat as absent/cleared → null
 * - [Patched.Some]`(value)` → use new value
 */
private fun resolveField(patched: Patched<String>, existing: String?): String? =
    when (patched) {
        Patched.Undefined -> existing?.takeIf { it.isNotBlank() }
        is Patched.Some -> patched.value?.takeIf { it.isNotBlank() }
    }

private fun patchBluesky(existing: BlueskyConfig?, patch: BlueskySettingsPatch): BlueskyConfig? {
    val service = resolveField(patch.service, existing?.service) ?: "https://bsky.social"
    val username = resolveField(patch.username, existing?.username) ?: return null
    val password = resolveField(patch.password, existing?.password) ?: return null
    return existing?.copy(service = service, username = username, password = password)
        ?: BlueskyConfig(service = service, username = username, password = password)
}

private fun patchMastodon(
    existing: MastodonConfig?,
    patch: MastodonSettingsPatch,
): MastodonConfig? {
    val host = resolveField(patch.host, existing?.host) ?: return null
    val accessToken = resolveField(patch.accessToken, existing?.accessToken) ?: return null
    return MastodonConfig(host = host, accessToken = accessToken)
}

private fun patchTwitter(existing: TwitterConfig?, patch: TwitterSettingsPatch): TwitterConfig? {
    val key = resolveField(patch.oauth1ConsumerKey, existing?.oauth1ConsumerKey) ?: return null
    val secret =
        resolveField(patch.oauth1ConsumerSecret, existing?.oauth1ConsumerSecret) ?: return null
    return existing?.copy(oauth1ConsumerKey = key, oauth1ConsumerSecret = secret)
        ?: TwitterConfig(oauth1ConsumerKey = key, oauth1ConsumerSecret = secret)
}

private fun patchLinkedIn(
    existing: LinkedInConfig?,
    patch: LinkedInSettingsPatch,
): LinkedInConfig? {
    val clientId = resolveField(patch.clientId, existing?.clientId) ?: return null
    val clientSecret = resolveField(patch.clientSecret, existing?.clientSecret) ?: return null
    return existing?.copy(clientId = clientId, clientSecret = clientSecret)
        ?: LinkedInConfig(clientId = clientId, clientSecret = clientSecret)
}

private fun patchMetaThreads(
    existing: MetaThreadsConfig?,
    patch: MetaThreadsSettingsPatch,
): MetaThreadsConfig? {
    val userId = resolveField(patch.userId, existing?.userId) ?: return null
    val accessToken = resolveField(patch.accessToken, existing?.accessToken) ?: return null
    return existing?.copy(userId = userId, accessToken = accessToken)
        ?: MetaThreadsConfig(userId = userId, accessToken = accessToken)
}

private fun patchLlm(existing: LlmConfig?, patch: LlmSettingsPatch): LlmConfig? {
    val apiUrl = resolveField(patch.apiUrl, existing?.apiUrl) ?: return null
    val apiKey = resolveField(patch.apiKey, existing?.apiKey) ?: return null
    val model = resolveField(patch.model, existing?.model) ?: ""
    return LlmConfig(apiUrl = apiUrl, apiKey = apiKey, model = model)
}

/**
 * Applies [patch] to [existing] settings using JSON Merge Patch semantics.
 *
 * Top-level section rules:
 * - [Patched.Undefined] → keep existing section unchanged
 * - [Patched.Some]`(null)` → remove section
 * - [Patched.Some]`(patch)` → merge field-by-field
 */
internal fun mergeSettingsPatch(existing: UserSettings?, patch: UserSettingsPatch): UserSettings =
    UserSettings(
        bluesky =
            when (val p = patch.bluesky) {
                Patched.Undefined -> existing?.bluesky
                is Patched.Some -> p.value?.let { patchBluesky(existing?.bluesky, it) }
            },
        mastodon =
            when (val p = patch.mastodon) {
                Patched.Undefined -> existing?.mastodon
                is Patched.Some -> p.value?.let { patchMastodon(existing?.mastodon, it) }
            },
        twitter =
            when (val p = patch.twitter) {
                Patched.Undefined -> existing?.twitter
                is Patched.Some -> p.value?.let { patchTwitter(existing?.twitter, it) }
            },
        linkedin =
            when (val p = patch.linkedin) {
                Patched.Undefined -> existing?.linkedin
                is Patched.Some -> p.value?.let { patchLinkedIn(existing?.linkedin, it) }
            },
        metaThreads =
            when (val p = patch.metaThreads) {
                Patched.Undefined -> existing?.metaThreads
                is Patched.Some -> p.value?.let { patchMetaThreads(existing?.metaThreads, it) }
            },
        llm =
            when (val p = patch.llm) {
                Patched.Undefined -> existing?.llm
                is Patched.Some -> p.value?.let { patchLlm(existing?.llm, it) }
            },
    )
