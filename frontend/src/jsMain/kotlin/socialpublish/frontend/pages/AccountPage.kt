package socialpublish.frontend.pages

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.components.Authorize
import socialpublish.frontend.components.PageContainer
import socialpublish.frontend.components.TextInputField
import socialpublish.frontend.models.ConfiguredServices
import socialpublish.frontend.utils.ApiClient
import socialpublish.frontend.utils.ApiResponse
import socialpublish.frontend.utils.Storage
import socialpublish.frontend.utils.buildLoginRedirectPath
import socialpublish.frontend.utils.isUnauthorized
import socialpublish.frontend.utils.navigateTo

@Serializable
data class TwitterStatusResponse(val hasAuthorization: Boolean, val createdAt: Long? = null)

@Serializable
data class LinkedInStatusResponse(val hasAuthorization: Boolean, val createdAt: Long? = null)

// ---------------------------------------------------------------------------
// DTOs — GET response (AccountSettingsView from the backend)
// ---------------------------------------------------------------------------

@Serializable
internal data class BlueskySettingsView(
    val service: String,
    val username: String,
    val password: String,
)

@Serializable internal data class MastodonSettingsView(val host: String, val accessToken: String)

@Serializable
internal data class TwitterSettingsView(
    val oauth1ConsumerKey: String,
    val oauth1ConsumerSecret: String,
)

@Serializable
internal data class LinkedInSettingsView(val clientId: String, val clientSecret: String)

@Serializable
internal data class LlmSettingsView(val apiUrl: String, val apiKey: String, val model: String)

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
 * Sensitive fields (passwords, tokens, secrets, API keys) start empty. The server returns "****"
 * for sensitive fields that have stored values — this is surfaced as a `*IsSet` flag so the UI can
 * show a "value is stored; leave blank to keep" hint without pre-filling the input.
 *
 * Non-sensitive fields (URLs, usernames, IDs) are pre-populated from the server response.
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
)

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
        twitterConsumerSecretIsSet = twitter?.oauth1ConsumerSecret?.isNotBlank() == true,
        twitterConsumerSecret = "",
        linkedinClientId = linkedin?.clientId ?: "",
        linkedinClientSecretIsSet = linkedin?.clientSecret?.isNotBlank() == true,
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
)

internal const val MASKED_SECRET_SENTINEL = "****"

internal fun mergeConfiguredServicesFromSettings(
    current: ConfiguredServices,
    fromSettings: ConfiguredServices,
): ConfiguredServices = fromSettings.copy(twitter = current.twitter, linkedin = current.linkedin)

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
        var state by remember { mutableStateOf(AccountPageState()) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            scope.launch {
                when (val response = ApiClient.get<AccountSettingsView>("/api/account/settings")) {
                    is ApiResponse.Success -> {
                        state = state.copy(formState = response.data.toFormState())
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
                            Storage.clearJwtToken()
                            Storage.setConfiguredServices(null)
                            navigateTo(buildLoginRedirectPath("/account"))
                            return@launch
                        }
                    }
                    is ApiResponse.Exception -> {}
                }
            }

            scope.launch {
                when (val response = ApiClient.get<TwitterStatusResponse>("/api/twitter/status")) {
                    is ApiResponse.Success -> {
                        val data = response.data
                        state =
                            state.copy(
                                twitterStatus =
                                    if (data.hasAuthorization) {
                                        "Connected" +
                                            (data.createdAt?.let {
                                                " at ${kotlin.js.Date(it).toLocaleString()}"
                                            } ?: "")
                                    } else "Not connected"
                            )
                        val services = Storage.getConfiguredServices()
                        Storage.setConfiguredServices(
                            applyTwitterAuthorizationStatus(services, data.hasAuthorization)
                        )
                    }
                    is ApiResponse.Error -> {
                        if (isUnauthorized(response)) {
                            Storage.clearJwtToken()
                            Storage.setConfiguredServices(null)
                            navigateTo(buildLoginRedirectPath("/account"))
                            return@launch
                        }
                        state = state.copy(twitterStatus = "Error: HTTP ${response.code}")
                    }
                    is ApiResponse.Exception ->
                        state = state.copy(twitterStatus = "Error: ${response.message}")
                }
            }

            scope.launch {
                when (
                    val response = ApiClient.get<LinkedInStatusResponse>("/api/linkedin/status")
                ) {
                    is ApiResponse.Success -> {
                        val data = response.data
                        state =
                            state.copy(
                                linkedInStatus =
                                    if (data.hasAuthorization) {
                                        "Connected" +
                                            (data.createdAt?.let {
                                                " at ${kotlin.js.Date(it).toLocaleString()}"
                                            } ?: "")
                                    } else "Not connected"
                            )
                        val services = Storage.getConfiguredServices()
                        Storage.setConfiguredServices(
                            applyLinkedInAuthorizationStatus(services, data.hasAuthorization)
                        )
                    }
                    is ApiResponse.Error -> {
                        if (isUnauthorized(response)) {
                            Storage.clearJwtToken()
                            Storage.setConfiguredServices(null)
                            navigateTo(buildLoginRedirectPath("/account"))
                            return@launch
                        }
                        state = state.copy(linkedInStatus = "Error: HTTP ${response.code}")
                    }
                    is ApiResponse.Exception ->
                        state = state.copy(linkedInStatus = "Error: ${response.message}")
                }
            }
        }

        val saveSettings: (SettingsFormState) -> Unit = { formState ->
            scope.launch {
                try {
                    state = state.copy(settingsSaved = false, settingsError = null)
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
                                Storage.clearJwtToken()
                                Storage.setConfiguredServices(null)
                                navigateTo(buildLoginRedirectPath("/account"))
                                return@launch
                            }
                            state =
                                state.copy(
                                    settingsError = "Failed to save settings: ${response.message}"
                                )
                        }
                        is ApiResponse.Exception ->
                            state = state.copy(settingsError = "Error: ${response.message}")
                    }
                } finally {
                    window.scrollTo(0.0, 0.0)
                }
            }
        }

        val authorizeTwitter: () -> Unit = { window.location.href = "/api/twitter/authorize" }
        val authorizeLinkedIn: () -> Unit = {
            scope.launch {
                when (
                    val response = ApiClient.get<LinkedInStatusResponse>("/api/linkedin/status")
                ) {
                    is ApiResponse.Success -> window.location.href = "/api/linkedin/authorize"
                    is ApiResponse.Error -> {
                        if (isUnauthorized(response)) {
                            Storage.clearJwtToken()
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

        PageContainer("account") {
            Div(attrs = { classes("block") }) {
                H1(attrs = { classes("title") }) { Text("Account Settings") }
            }

            if (state.settingsSaved) {
                Div(attrs = { classes("notification", "is-success", "is-light") }) {
                    Button(
                        attrs = {
                            classes("delete")
                            onClick { state = state.copy(settingsSaved = false) }
                        }
                    )
                    Text("Settings saved successfully!")
                }
            }
            if (state.settingsError != null) {
                Div(attrs = { classes("notification", "is-danger", "is-light") }) {
                    Button(
                        attrs = {
                            classes("delete")
                            onClick { state = state.copy(settingsError = null) }
                        }
                    )
                    Text(state.settingsError ?: "")
                }
            }

            SettingsForm(
                state = state.formState,
                onStateChange = { state = state.copy(formState = it) },
                onSave = saveSettings,
            )

            // OAuth connections box
            Div(attrs = { classes("box", "mt-4") }) {
                H2(attrs = { classes("subtitle") }) { Text("OAuth Connections") }
                P(attrs = { classes("help", "mb-3") }) {
                    Text(
                        "After configuring Twitter/LinkedIn credentials above, connect here to authorize posting."
                    )
                }

                Button(
                    attrs = {
                        classes("button", "is-link")
                        onClick { authorizeTwitter() }
                    }
                ) {
                    Span(attrs = { classes("icon") }) {
                        I(attrs = { classes("fab", "fa-x-twitter") })
                    }
                    Span(attrs = { style { fontWeight("bold") } }) { Text("Connect X (Twitter)") }
                }
                P(attrs = { classes("help") }) { Text(state.twitterStatus) }

                Br()

                Button(
                    attrs = {
                        classes("button", "is-info")
                        onClick { authorizeLinkedIn() }
                    }
                ) {
                    Span(attrs = { classes("icon") }) {
                        I(attrs = { classes("fa-brands", "fa-square-linkedin") })
                    }
                    Span(attrs = { style { fontWeight("bold") } }) { Text("Connect LinkedIn") }
                }
                P(attrs = { classes("help") }) { Text(state.linkedInStatus) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun AccountSettingsView.toConfiguredServices() =
    socialpublish.frontend.models.ConfiguredServices(
        mastodon = mastodon != null,
        bluesky = bluesky != null,
        twitter = false,
        linkedin = false,
        llm = llm != null,
    )

/**
 * Builds the JSON body for PATCH /api/account/settings using JSON Merge Patch semantics.
 *
 * Rules encoded here:
 * - Section key **absent** → backend keeps existing section (Patched.Undefined)
 * - Section key = **null** → backend removes the section (Patched.Some(null))
 * - Section key = **object** → backend merges fields:
 *     - Field **absent** → keep existing (Patched.Undefined on the backend)
 *     - Field **present** → update to new value (Patched.Some(value))
 *
 * Sensitive fields (password, token, key, secret) are left out of the object when blank, so the
 * backend keeps the existing credential unchanged. To remove a whole section the user must clear
 * both the identifying non-sensitive field AND leave the sensitive field blank; in that case we
 * send the section as explicit `null`.
 */
internal fun SettingsFormState.toPatchBody(): JsonObject = buildJsonObject {
    // Bluesky — username is the required identifier
    when {
        blueskyUsername.isNotBlank() ->
            putJsonObject("bluesky") {
                put("service", blueskyService.ifBlank { "https://bsky.social" })
                put("username", blueskyUsername)
                if (blueskyPassword.isNotBlank() && blueskyPassword != MASKED_SECRET_SENTINEL) {
                    put("password", blueskyPassword)
                }
            }
        // User cleared the username (and it was previously set) → remove the section
        blueskyPasswordIsSet || blueskyUsername.isBlank() && blueskyService.isNotBlank() ->
            put("bluesky", JsonNull)
    // else: section was never configured and user left it empty → omit (no change)
    }

    // Mastodon — host is the required identifier
    when {
        mastodonHost.isNotBlank() ->
            putJsonObject("mastodon") {
                put("host", mastodonHost)
                if (mastodonToken.isNotBlank() && mastodonToken != MASKED_SECRET_SENTINEL) {
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
                if (llmApiKey.isNotBlank() && llmApiKey != MASKED_SECRET_SENTINEL) {
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
        // Bluesky
        Div(attrs = { classes("box", "mb-4") }) {
            H2(attrs = { classes("subtitle") }) { Text("Bluesky") }
            TextInputField(
                label = "Service URL",
                value = state.blueskyService,
                onValueChange = { onStateChange(state.copy(blueskyService = it)) },
                placeholder = "https://bsky.social",
            )
            TextInputField(
                label = "Username",
                value = state.blueskyUsername,
                onValueChange = { onStateChange(state.copy(blueskyUsername = it)) },
                placeholder = "user.bsky.social",
            )
            TextInputField(
                label = "App Password",
                value = state.blueskyPassword,
                onValueChange = { onStateChange(state.copy(blueskyPassword = it)) },
                placeholder =
                    if (state.blueskyPasswordIsSet) "leave blank to keep existing"
                    else "xxxx-xxxx-xxxx-xxxx",
                type = InputType.Password,
            )
        }

        // Mastodon
        Div(attrs = { classes("box", "mb-4") }) {
            H2(attrs = { classes("subtitle") }) { Text("Mastodon") }
            TextInputField(
                label = "Host URL",
                value = state.mastodonHost,
                onValueChange = { onStateChange(state.copy(mastodonHost = it)) },
                placeholder = "https://mastodon.social",
            )
            TextInputField(
                label = "Access Token",
                value = state.mastodonToken,
                onValueChange = { onStateChange(state.copy(mastodonToken = it)) },
                placeholder =
                    if (state.mastodonTokenIsSet) "leave blank to keep existing"
                    else "Your Mastodon access token",
                type = InputType.Password,
            )
        }

        // Twitter / X
        Div(attrs = { classes("box", "mb-4") }) {
            H2(attrs = { classes("subtitle") }) { Text("X (Twitter) – App Credentials") }
            P(attrs = { classes("help", "mb-2") }) {
                Text(
                    "Consumer key and secret from your Twitter Developer App. After saving, use the OAuth button below to authorize."
                )
            }
            TextInputField(
                label = "Consumer Key",
                value = state.twitterConsumerKey,
                onValueChange = { onStateChange(state.copy(twitterConsumerKey = it)) },
                placeholder = "OAuth1 Consumer Key",
            )
            TextInputField(
                label = "Consumer Secret",
                value = state.twitterConsumerSecret,
                onValueChange = { onStateChange(state.copy(twitterConsumerSecret = it)) },
                placeholder =
                    if (state.twitterConsumerSecretIsSet) "leave blank to keep existing"
                    else "OAuth1 Consumer Secret",
                type = InputType.Password,
            )
        }

        // LinkedIn
        Div(attrs = { classes("box", "mb-4") }) {
            H2(attrs = { classes("subtitle") }) { Text("LinkedIn – App Credentials") }
            P(attrs = { classes("help", "mb-2") }) {
                Text(
                    "Client ID and secret from your LinkedIn Developer App. After saving, use the OAuth button below to authorize."
                )
            }
            TextInputField(
                label = "Client ID",
                value = state.linkedinClientId,
                onValueChange = { onStateChange(state.copy(linkedinClientId = it)) },
                placeholder = "LinkedIn Client ID",
            )
            TextInputField(
                label = "Client Secret",
                value = state.linkedinClientSecret,
                onValueChange = { onStateChange(state.copy(linkedinClientSecret = it)) },
                placeholder =
                    if (state.linkedinClientSecretIsSet) "leave blank to keep existing"
                    else "LinkedIn Client Secret",
                type = InputType.Password,
            )
        }

        // LLM
        Div(attrs = { classes("box", "mb-4") }) {
            H2(attrs = { classes("subtitle") }) { Text("LLM (AI alt-text generation)") }
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
                    if (state.llmApiKeyIsSet) "leave blank to keep existing" else "sk-...",
                type = InputType.Password,
            )
            TextInputField(
                label = "Model",
                value = state.llmModel,
                onValueChange = { onStateChange(state.copy(llmModel = it)) },
                placeholder = "gpt-4o-mini",
            )
        }

        Button(
            attrs = {
                classes("button", "is-primary")
                attr("type", "submit")
            }
        ) {
            Span(attrs = { classes("icon") }) { I(attrs = { classes("fas", "fa-save") }) }
            Span { Text("Save Settings") }
        }
    }
}
