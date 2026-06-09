package socialpublish.frontend.pages

import androidx.compose.runtime.*
import kotlin.js.Date
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.components.Authorize
import socialpublish.frontend.components.CollapsibleSection
import socialpublish.frontend.components.ErrorModal
import socialpublish.frontend.components.NotificationMessage
import socialpublish.frontend.components.NotificationType
import socialpublish.frontend.components.PageContainer
import socialpublish.frontend.components.SectionDivider
import socialpublish.frontend.components.StatusBadge
import socialpublish.frontend.components.TextInputField
import socialpublish.frontend.utils.ApiClient
import socialpublish.frontend.utils.ApiResponse
import socialpublish.frontend.utils.ConfiguredServices
import socialpublish.frontend.utils.Storage
import socialpublish.frontend.utils.buildLoginRedirectPath
import socialpublish.frontend.utils.isUnauthorized
import socialpublish.frontend.utils.navigateTo

@Serializable
data class TwitterStatusResponse(
    val hasAuthorization: Boolean,
    val createdAt: Long? = null,
)

@Serializable
data class LinkedInStatusResponse(
    val hasAuthorization: Boolean,
    val createdAt: Long? = null,
)

// ---------------------------------------------------------------------------
// DTOs — GET response (AccountSettingsView from the backend)
// ---------------------------------------------------------------------------

@Serializable
internal data class BlueskySettingsView(
    val service: String,
    val username: String,
    val password: String,
)

@Serializable
internal data class MastodonSettingsView(
    val host: String,
    val accessToken: String,
)

@Serializable
internal data class TwitterSettingsView(
    val oauth1ConsumerKey: String,
    val oauth1ConsumerSecret: String,
)

@Serializable
internal data class LinkedInSettingsView(
    val clientId: String,
    val clientSecret: String,
)

@Serializable
internal data class LlmSettingsView(
    val apiUrl: String,
    val apiKey: String,
    val model: String,
)

@Serializable
internal data class AccountSettingsView(
    val bluesky: BlueskySettingsView? = null,
    val mastodon: MastodonSettingsView? = null,
    val twitter: TwitterSettingsView? = null,
    val linkedin: LinkedInSettingsView? = null,
    val llm: LlmSettingsView? = null,
)

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/**
 * All form field values.
 *
 * Sensitive fields (passwords, tokens, secrets, API keys) start empty. The
 * server returns "****" for sensitive fields that have stored values — this is
 * surfaced as a `*IsSet` flag so the UI can show a "value is stored; leave
 * blank to keep" hint without pre-filling the input.
 *
 * Non-sensitive fields (URLs, usernames, IDs) are pre-populated from the server
 * response.
 */
internal data class SettingsFormState(
    val blueskyService: String = "https://bsky.social",
    val blueskyUsername: String = "",
    val blueskyPasswordIsSet: Boolean = false,
    val blueskyPassword: String = "",
    val mastodonHost: String = "",
    val mastodonTokenIsSet: Boolean = false,
    val mastodonToken: String = "",
    val twitterConsumerKey: String = "",
    val twitterConsumerSecretIsSet: Boolean = false,
    val twitterConsumerSecret: String = "",
    val linkedinClientId: String = "",
    val linkedinClientSecretIsSet: Boolean = false,
    val linkedinClientSecret: String = "",
    val llmApiUrl: String = "",
    val llmApiKeyIsSet: Boolean = false,
    val llmApiKey: String = "",
    val llmModel: String = "",
) {
    /**
     * A section is "configured" when its non-sensitive identifying field is set
     * (i.e. the backend returned a value for it). The `*IsSet` flags only
     * describe the sensitive sub-field, so they are not the right signal here.
     */
    val isBlueskyConfigured: Boolean
        get() = blueskyUsername.isNotBlank()

    val isMastodonConfigured: Boolean
        get() = mastodonHost.isNotBlank()

    val isTwitterConfigured: Boolean
        get() = twitterConsumerKey.isNotBlank()

    val isLinkedInConfigured: Boolean
        get() = linkedinClientId.isNotBlank()

    val isLlmConfigured: Boolean
        get() = llmApiUrl.isNotBlank()
}

internal fun AccountSettingsView.toFormState(): SettingsFormState =
    SettingsFormState(
        blueskyService = bluesky?.service ?: "https://bsky.social",
        blueskyUsername = bluesky?.username ?: "",
        blueskyPasswordIsSet = bluesky?.password?.isNotBlank() == true,
        blueskyPassword = "",
        mastodonHost = mastodon?.host ?: "",
        mastodonTokenIsSet = mastodon?.accessToken?.isNotBlank() == true,
        mastodonToken = "",
        twitterConsumerKey = twitter?.oauth1ConsumerKey ?: "",
        twitterConsumerSecretIsSet =
            twitter?.oauth1ConsumerSecret?.isNotBlank() == true,
        twitterConsumerSecret = "",
        linkedinClientId = linkedin?.clientId ?: "",
        linkedinClientSecretIsSet =
            linkedin?.clientSecret?.isNotBlank() == true,
        linkedinClientSecret = "",
        llmApiUrl = llm?.apiUrl ?: "",
        llmApiKeyIsSet = llm?.apiKey?.isNotBlank() == true,
        llmApiKey = "",
        llmModel = llm?.model ?: "",
    )

/** Page-level state for the Account page. */
private data class AccountPageState(
    val twitterStatus: String = "Querying...",
    val linkedInStatus: String = "Querying...",
    val formState: SettingsFormState = SettingsFormState(),
    val settingsSaved: Boolean = false,
    val settingsError: String? = null,
    val oauthError: String? = null,
    val oauthInfo: String? = null,
)

internal const val MASKED_SECRET_SENTINEL = "****"

internal fun mergeConfiguredServicesFromSettings(
    current: ConfiguredServices,
    fromSettings: ConfiguredServices,
): ConfiguredServices =
    fromSettings.copy(twitter = current.twitter, linkedin = current.linkedin)

internal fun applyTwitterAuthorizationStatus(
    current: ConfiguredServices,
    hasAuthorization: Boolean,
): ConfiguredServices = current.copy(twitter = hasAuthorization)

internal fun applyLinkedInAuthorizationStatus(
    current: ConfiguredServices,
    hasAuthorization: Boolean,
): ConfiguredServices = current.copy(linkedin = hasAuthorization)

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

@Composable
fun AccountPage() {
    Authorize {
        var state by remember {
            val params = URLSearchParams(window.location.search)
            mutableStateOf(
                AccountPageState(
                    oauthError = params.get("error"),
                    oauthInfo = params.get("info"),
                )
            )
        }
        val scope = rememberCoroutineScope()

        LaunchedEffect(state.oauthError, state.oauthInfo) {
            if (state.oauthError != null || state.oauthInfo != null) {
                window.history.replaceState(null, "", "/account")
            }
        }

        LaunchedEffect(Unit) {
            scope.launch {
                when (
                    val response =
                        ApiClient.get<AccountSettingsView>(
                            "/api/account/settings"
                        )
                ) {
                    is ApiResponse.Success -> {
                        state =
                            state.copy(formState = response.data.toFormState())
                        val current = Storage.getConfiguredServices()
                        Storage.setConfiguredServices(
                            mergeConfiguredServicesFromSettings(
                                current,
                                response.data.toConfiguredServices(),
                            )
                        )
                    }
                    is ApiResponse.Error -> {
                        if (isUnauthorized(response)) {
                            Storage.clearSessionToken()
                            Storage.setConfiguredServices(null)
                            navigateTo(buildLoginRedirectPath("/account"))
                            return@launch
                        }
                    }
                    is ApiResponse.Exception -> {}
                }
            }

            scope.launch {
                when (
                    val response =
                        ApiClient.get<TwitterStatusResponse>(
                            "/api/twitter/status"
                        )
                ) {
                    is ApiResponse.Success -> {
                        val data = response.data
                        state =
                            state.copy(
                                twitterStatus =
                                    if (data.hasAuthorization) {
                                        "Connected" +
                                            (data.createdAt?.let {
                                                " at ${Date(it).toLocaleString()}"
                                            } ?: "")
                                    } else "Not connected"
                            )
                        val services = Storage.getConfiguredServices()
                        Storage.setConfiguredServices(
                            applyTwitterAuthorizationStatus(
                                services,
                                data.hasAuthorization,
                            )
                        )
                    }
                    is ApiResponse.Error -> {
                        if (isUnauthorized(response)) {
                            Storage.clearSessionToken()
                            Storage.setConfiguredServices(null)
                            navigateTo(buildLoginRedirectPath("/account"))
                            return@launch
                        }
                        state =
                            state.copy(
                                twitterStatus = "Error: HTTP ${response.code}"
                            )
                    }
                    is ApiResponse.Exception ->
                        state =
                            state.copy(
                                twitterStatus = "Error: ${response.message}"
                            )
                }
            }

            scope.launch {
                when (
                    val response =
                        ApiClient.get<LinkedInStatusResponse>(
                            "/api/linkedin/status"
                        )
                ) {
                    is ApiResponse.Success -> {
                        val data = response.data
                        state =
                            state.copy(
                                linkedInStatus =
                                    if (data.hasAuthorization) {
                                        "Connected" +
                                            (data.createdAt?.let {
                                                " at ${Date(it).toLocaleString()}"
                                            } ?: "")
                                    } else "Not connected"
                            )
                        val services = Storage.getConfiguredServices()
                        Storage.setConfiguredServices(
                            applyLinkedInAuthorizationStatus(
                                services,
                                data.hasAuthorization,
                            )
                        )
                    }
                    is ApiResponse.Error -> {
                        if (isUnauthorized(response)) {
                            Storage.clearSessionToken()
                            Storage.setConfiguredServices(null)
                            navigateTo(buildLoginRedirectPath("/account"))
                            return@launch
                        }
                        state =
                            state.copy(
                                linkedInStatus = "Error: HTTP ${response.code}"
                            )
                    }
                    is ApiResponse.Exception ->
                        state =
                            state.copy(
                                linkedInStatus = "Error: ${response.message}"
                            )
                }
            }
        }

        val saveSettings: (SettingsFormState) -> Unit = { formState ->
            scope.launch {
                try {
                    state =
                        state.copy(settingsSaved = false, settingsError = null)
                    val patchBody = formState.toPatchBody()
                    when (
                        val response =
                            ApiClient.patch<AccountSettingsView, JsonObject>(
                                "/api/account/settings",
                                patchBody,
                            )
                    ) {
                        is ApiResponse.Success -> {
                            state =
                                state.copy(
                                    settingsSaved = true,
                                    formState = response.data.toFormState(),
                                )
                            val current = Storage.getConfiguredServices()
                            Storage.setConfiguredServices(
                                mergeConfiguredServicesFromSettings(
                                    current,
                                    response.data.toConfiguredServices(),
                                )
                            )
                        }
                        is ApiResponse.Error -> {
                            if (isUnauthorized(response)) {
                                Storage.clearSessionToken()
                                Storage.setConfiguredServices(null)
                                navigateTo(buildLoginRedirectPath("/account"))
                                return@launch
                            }
                            state =
                                state.copy(
                                    settingsError =
                                        "Failed to save settings: ${response.message}"
                                )
                        }
                        is ApiResponse.Exception ->
                            state =
                                state.copy(
                                    settingsError = "Error: ${response.message}"
                                )
                    }
                } finally {
                    window.scrollTo(0.0, 0.0)
                }
            }
        }

        val authorizeTwitter: () -> Unit = {
            window.location.href = "/api/twitter/authorize"
        }
        val authorizeLinkedIn: () -> Unit = {
            scope.launch {
                when (
                    val response =
                        ApiClient.get<LinkedInStatusResponse>(
                            "/api/linkedin/status"
                        )
                ) {
                    is ApiResponse.Success ->
                        window.location.href = "/api/linkedin/authorize"
                    is ApiResponse.Error -> {
                        if (isUnauthorized(response)) {
                            Storage.clearSessionToken()
                            Storage.setConfiguredServices(null)
                            navigateTo(buildLoginRedirectPath("/account"))
                            return@launch
                        }
                        if (response.code == 503 || response.code == 500) {
                            window.alert(
                                "LinkedIn integration is not configured. " +
                                    "Please configure your LinkedIn credentials in Account Settings."
                            )
                        } else {
                            window.location.href = "/api/linkedin/authorize"
                        }
                    }
                    is ApiResponse.Exception ->
                        window.alert("Could not connect: ${response.message}")
                }
            }
        }

        ErrorModal(message = state.oauthError) {
            state = state.copy(oauthError = null)
        }

        PageContainer("account") {
            Div(attrs = { classes("block") }) {
                H1(attrs = { classes("title") }) { Text("Account Settings") }
            }

            state.oauthInfo?.let { info ->
                NotificationMessage(
                    message = info,
                    type = NotificationType.SUCCESS,
                    onDismiss = { state = state.copy(oauthInfo = null) },
                )
            }

            if (state.settingsSaved) {
                NotificationMessage(
                    message = "Settings saved successfully!",
                    type = NotificationType.SUCCESS,
                    onDismiss = { state = state.copy(settingsSaved = false) },
                )
            }
            state.settingsError?.let { error ->
                NotificationMessage(
                    message = error,
                    type = NotificationType.ERROR,
                    onDismiss = { state = state.copy(settingsError = null) },
                )
            }

            SettingsForm(
                state = state.formState,
                onStateChange = { state = state.copy(formState = it) },
                onSave = saveSettings,
            )

            SectionDivider(label = "Authorization", icon = "fa-plug")

            // OAuth connections box
            CollapsibleSection(
                title = "OAuth Connections",
                icon = "fa-plug",
                summary = {
                    OAuthConnectionsBadge(
                        statuses =
                            listOf(state.twitterStatus, state.linkedInStatus)
                    )
                },
            ) {
                P(attrs = { classes("help", "mb-3") }) {
                    Text(
                        "After configuring Twitter/LinkedIn credentials above, connect here to authorize posting."
                    )
                }

                Div(
                    attrs = {
                        classes(
                            "is-flex",
                            "is-justify-content-space-between",
                            "is-align-items-center",
                            "mb-1",
                        )
                    }
                ) {
                    Button(
                        attrs = {
                            classes("button", "is-link")
                            onClick { authorizeTwitter() }
                        }
                    ) {
                        Span(attrs = { classes("icon") }) {
                            I(attrs = { classes("fab", "fa-x-twitter") })
                        }
                        Span(attrs = { classes("has-text-weight-bold") }) {
                            Text("Connect X (Twitter)")
                        }
                    }
                    P(attrs = { classes("help", "mb-0") }) {
                        Text(state.twitterStatus)
                    }
                }

                Div(
                    attrs = {
                        classes(
                            "is-flex",
                            "is-justify-content-space-between",
                            "is-align-items-center",
                        )
                    }
                ) {
                    Button(
                        attrs = {
                            classes("button", "is-info")
                            onClick { authorizeLinkedIn() }
                        }
                    ) {
                        Span(attrs = { classes("icon") }) {
                            I(
                                attrs = {
                                    classes("fa-brands", "fa-square-linkedin")
                                }
                            )
                        }
                        Span(attrs = { classes("has-text-weight-bold") }) {
                            Text("Connect LinkedIn")
                        }
                    }
                    P(attrs = { classes("help", "mb-0") }) {
                        Text(state.linkedInStatus)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun AccountSettingsView.toConfiguredServices() =
    ConfiguredServices(
        mastodon = mastodon != null,
        bluesky = bluesky != null,
        twitter = false,
        linkedin = false,
        llm = llm != null,
    )

/**
 * Builds the JSON body for PATCH /api/account/settings using JSON Merge Patch
 * semantics.
 *
 * Rules encoded here:
 * - Section key **absent** → backend keeps existing section (Patched.Undefined)
 * - Section key = **null** → backend removes the section (Patched.Some(null))
 * - Section key = **object** → backend merges fields:
 *     - Field **absent** → keep existing (Patched.Undefined on the backend)
 *     - Field **present** → update to new value (Patched.Some(value))
 *
 * Sensitive fields (password, token, key, secret) are left out of the object
 * when blank, so the backend keeps the existing credential unchanged. To remove
 * a whole section the user must clear both the identifying non-sensitive field
 * AND leave the sensitive field blank; in that case we send the section as
 * explicit `null`.
 */
internal fun SettingsFormState.toPatchBody(): JsonObject = buildJsonObject {
    // Bluesky — username is the required identifier
    when {
        blueskyUsername.isNotBlank() ->
            putJsonObject("bluesky") {
                put("service", blueskyService.ifBlank { "https://bsky.social" })
                put("username", blueskyUsername)
                if (
                    blueskyPassword.isNotBlank() &&
                        blueskyPassword != MASKED_SECRET_SENTINEL
                ) {
                    put("password", blueskyPassword)
                }
            }
        // User cleared the username (and it was previously set) → remove the
        // section
        blueskyPasswordIsSet ||
            blueskyUsername.isBlank() && blueskyService.isNotBlank() ->
            put("bluesky", JsonNull)
    // else: section was never configured and user left it empty → omit (no
    // change)
    }

    // Mastodon — host is the required identifier
    when {
        mastodonHost.isNotBlank() ->
            putJsonObject("mastodon") {
                put("host", mastodonHost)
                if (
                    mastodonToken.isNotBlank() &&
                        mastodonToken != MASKED_SECRET_SENTINEL
                ) {
                    put("accessToken", mastodonToken)
                }
            }
        mastodonTokenIsSet -> put("mastodon", JsonNull)
    }

    // Twitter — consumer key is the required identifier
    when {
        twitterConsumerKey.isNotBlank() ->
            putJsonObject("twitter") {
                put("oauth1ConsumerKey", twitterConsumerKey)
                if (
                    twitterConsumerSecret.isNotBlank() &&
                        twitterConsumerSecret != MASKED_SECRET_SENTINEL
                )
                    put("oauth1ConsumerSecret", twitterConsumerSecret)
            }
        twitterConsumerSecretIsSet -> put("twitter", JsonNull)
    }

    // LinkedIn — client ID is the required identifier
    when {
        linkedinClientId.isNotBlank() ->
            putJsonObject("linkedin") {
                put("clientId", linkedinClientId)
                if (
                    linkedinClientSecret.isNotBlank() &&
                        linkedinClientSecret != MASKED_SECRET_SENTINEL
                ) {
                    put("clientSecret", linkedinClientSecret)
                }
            }
        linkedinClientSecretIsSet -> put("linkedin", JsonNull)
    }

    // LLM — API URL is the required identifier
    when {
        llmApiUrl.isNotBlank() ->
            putJsonObject("llm") {
                put("apiUrl", llmApiUrl)
                if (
                    llmApiKey.isNotBlank() &&
                        llmApiKey != MASKED_SECRET_SENTINEL
                ) {
                    put("apiKey", llmApiKey)
                }
                put("model", llmModel)
            }
        llmApiKeyIsSet -> put("llm", JsonNull)
    }
}

// ---------------------------------------------------------------------------
// Settings form
// ---------------------------------------------------------------------------

@Composable
private fun SettingsForm(
    state: SettingsFormState,
    onStateChange: (SettingsFormState) -> Unit,
    onSave: (SettingsFormState) -> Unit,
) {
    Form(
        attrs = {
            addEventListener("submit") { event ->
                event.preventDefault()
                onSave(state)
            }
        }
    ) {
        CollapsibleSection(
            title = "Bluesky",
            icon = "fa-bluesky",
            iconPrefix = "fab",
            summary = { StatusBadge(isConfigured = state.isBlueskyConfigured) },
        ) {
            TextInputField(
                label = "Service URL",
                value = state.blueskyService,
                onValueChange = {
                    onStateChange(state.copy(blueskyService = it))
                },
                placeholder = "https://bsky.social",
            )
            TextInputField(
                label = "Username",
                value = state.blueskyUsername,
                onValueChange = {
                    onStateChange(state.copy(blueskyUsername = it))
                },
                placeholder = "user.bsky.social",
            )
            TextInputField(
                label = "App Password",
                value = state.blueskyPassword,
                onValueChange = {
                    onStateChange(state.copy(blueskyPassword = it))
                },
                placeholder =
                    if (state.blueskyPasswordIsSet)
                        "leave blank to keep existing"
                    else "xxxx-xxxx-xxxx-xxxx",
                type = InputType.Password,
            )
        }

        CollapsibleSection(
            title = "Mastodon",
            icon = "fa-mastodon",
            iconPrefix = "fab",
            summary = { StatusBadge(isConfigured = state.isMastodonConfigured) },
        ) {
            TextInputField(
                label = "Host URL",
                value = state.mastodonHost,
                onValueChange = {
                    onStateChange(state.copy(mastodonHost = it))
                },
                placeholder = "https://mastodon.social",
            )
            TextInputField(
                label = "Access Token",
                value = state.mastodonToken,
                onValueChange = {
                    onStateChange(state.copy(mastodonToken = it))
                },
                placeholder =
                    if (state.mastodonTokenIsSet) "leave blank to keep existing"
                    else "Your Mastodon access token",
                type = InputType.Password,
            )
        }

        CollapsibleSection(
            title = "X (Twitter)",
            icon = "fa-x-twitter",
            iconPrefix = "fab",
            summary = { StatusBadge(isConfigured = state.isTwitterConfigured) },
        ) {
            P(attrs = { classes("help", "mb-3") }) {
                Text(
                    "Consumer key and secret from your Twitter Developer App. " +
                        "After saving, use the OAuth Connections section below to authorize."
                )
            }
            TextInputField(
                label = "Consumer Key",
                value = state.twitterConsumerKey,
                onValueChange = {
                    onStateChange(state.copy(twitterConsumerKey = it))
                },
                placeholder = "OAuth1 Consumer Key",
            )
            TextInputField(
                label = "Consumer Secret",
                value = state.twitterConsumerSecret,
                onValueChange = {
                    onStateChange(state.copy(twitterConsumerSecret = it))
                },
                placeholder =
                    if (state.twitterConsumerSecretIsSet)
                        "leave blank to keep existing"
                    else "OAuth1 Consumer Secret",
                type = InputType.Password,
            )
        }

        CollapsibleSection(
            title = "LinkedIn",
            icon = "fa-linkedin",
            iconPrefix = "fab",
            summary = { StatusBadge(isConfigured = state.isLinkedInConfigured) },
        ) {
            P(attrs = { classes("help", "mb-3") }) {
                Text(
                    "Client ID and secret from your LinkedIn Developer App. " +
                        "After saving, use the OAuth Connections section below to authorize."
                )
            }
            TextInputField(
                label = "Client ID",
                value = state.linkedinClientId,
                onValueChange = {
                    onStateChange(state.copy(linkedinClientId = it))
                },
                placeholder = "LinkedIn Client ID",
            )
            TextInputField(
                label = "Client Secret",
                value = state.linkedinClientSecret,
                onValueChange = {
                    onStateChange(state.copy(linkedinClientSecret = it))
                },
                placeholder =
                    if (state.linkedinClientSecretIsSet)
                        "leave blank to keep existing"
                    else "LinkedIn Client Secret",
                type = InputType.Password,
            )
        }

        CollapsibleSection(
            title = "AI for alt-text",
            icon = "fa-robot",
            summary = { StatusBadge(isConfigured = state.isLlmConfigured) },
        ) {
            TextInputField(
                label = "API URL",
                value = state.llmApiUrl,
                onValueChange = { onStateChange(state.copy(llmApiUrl = it)) },
                placeholder = "https://api.openai.com/v1/chat/completions",
            )
            TextInputField(
                label = "API Key",
                value = state.llmApiKey,
                onValueChange = { onStateChange(state.copy(llmApiKey = it)) },
                placeholder =
                    if (state.llmApiKeyIsSet) "leave blank to keep existing"
                    else "sk-...",
                type = InputType.Password,
            )
            TextInputField(
                label = "Model",
                value = state.llmModel,
                onValueChange = { onStateChange(state.copy(llmModel = it)) },
                placeholder = "gpt-4o-mini",
            )
        }

        Div(
            attrs = {
                classes(
                    "field",
                    "is-grouped",
                    "is-justify-content-flex-end",
                    "mt-2",
                )
            }
        ) {
            Div(attrs = { classes("control") }) {
                Button(
                    attrs = {
                        classes("button", "is-primary")
                        attr("type", "submit")
                    }
                ) {
                    Span(attrs = { classes("icon") }) {
                        I(attrs = { classes("fas", "fa-save") })
                    }
                    Span { Text("Save Settings") }
                }
            }
        }
    }
}

/**
 * Tri-state pill for the OAuth Connections section. A connection is considered
 * active when its status string starts with "Connected". Local to this page
 * because the parsing rule ("Connected...") is page-specific.
 */
@Composable
private fun OAuthConnectionsBadge(statuses: List<String>) {
    val connected = statuses.count { it.startsWith("Connected") }
    val (color, text) =
        when {
            connected == 0 -> "is-light" to "Not connected"
            connected == statuses.size -> "is-success" to "All connected"
            else -> "is-warning" to "$connected/${statuses.size} connected"
        }
    Span(
        attrs = {
            classes("tag", "is-rounded", color)
            if (color == "is-light") attr("has-text-grey", "")
        }
    ) {
        I(attrs = { classes("fas", "fa-plug", "mr-1") })
        Text(text)
    }
}
