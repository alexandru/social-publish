package socialpublish.frontend.pages

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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

@Serializable
data class TwitterStatusResponse(val hasAuthorization: Boolean, val createdAt: Long? = null)

@Serializable
data class LinkedInStatusResponse(val hasAuthorization: Boolean, val createdAt: Long? = null)

// PUT body matching backend UserSettings (BlueskyConfig / MastodonConfig / etc.)
@Serializable
private data class BlueskySettingsBody(
    val service: String,
    val username: String,
    val password: String,
)

@Serializable private data class MastodonSettingsBody(val host: String, val accessToken: String)

@Serializable
private data class TwitterSettingsBody(
    val oauth1ConsumerKey: String,
    val oauth1ConsumerSecret: String,
)

@Serializable
private data class LinkedInSettingsBody(val clientId: String, val clientSecret: String)

@Serializable
private data class LlmSettingsBody(val apiUrl: String, val apiKey: String, val model: String)

@Serializable
private data class UserSettingsBody(
    val bluesky: BlueskySettingsBody? = null,
    val mastodon: MastodonSettingsBody? = null,
    val twitter: TwitterSettingsBody? = null,
    val linkedin: LinkedInSettingsBody? = null,
    val llm: LlmSettingsBody? = null,
)

/** All form field values for the settings form. Kept as a single immutable state. */
private data class SettingsFormState(
    val blueskyService: String = "https://bsky.social",
    val blueskyUsername: String = "",
    val blueskyPassword: String = "",
    val mastodonHost: String = "",
    val mastodonToken: String = "",
    val twitterConsumerKey: String = "",
    val twitterConsumerSecret: String = "",
    val linkedinClientId: String = "",
    val linkedinClientSecret: String = "",
    val llmApiUrl: String = "",
    val llmApiKey: String = "",
    val llmModel: String = "",
)

/** Page-level state for the Account page. */
private data class AccountPageState(
    val twitterStatus: String = "Querying...",
    val linkedInStatus: String = "Querying...",
    val formState: SettingsFormState = SettingsFormState(),
    val settingsSaved: Boolean = false,
    val settingsError: String? = null,
)

@Composable
fun AccountPage() {
    Authorize {
        var state by remember { mutableStateOf(AccountPageState()) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            scope.launch {
                when (val response = ApiClient.get<TwitterStatusResponse>("/api/twitter/status")) {
                    is ApiResponse.Success -> {
                        val data = response.data
                        val status =
                            if (data.hasAuthorization) {
                                "Connected" +
                                    (data.createdAt?.let {
                                        " at ${kotlin.js.Date(it).toLocaleString()}"
                                    } ?: "")
                            } else {
                                "Not connected"
                            }
                        state = state.copy(twitterStatus = status)
                        // Reflect OAuth status in configured services
                        val services = Storage.getConfiguredServices()
                        Storage.setConfiguredServices(
                            services.copy(twitter = data.hasAuthorization && services.twitter)
                        )
                    }
                    is ApiResponse.Error ->
                        state = state.copy(twitterStatus = "Error: HTTP ${response.code}")
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
                        val status =
                            if (data.hasAuthorization) {
                                "Connected" +
                                    (data.createdAt?.let {
                                        " at ${kotlin.js.Date(it).toLocaleString()}"
                                    } ?: "")
                            } else {
                                "Not connected"
                            }
                        state = state.copy(linkedInStatus = status)
                        val services = Storage.getConfiguredServices()
                        Storage.setConfiguredServices(
                            services.copy(linkedin = data.hasAuthorization && services.linkedin)
                        )
                    }
                    is ApiResponse.Error ->
                        state = state.copy(linkedInStatus = "Error: HTTP ${response.code}")
                    is ApiResponse.Exception ->
                        state = state.copy(linkedInStatus = "Error: ${response.message}")
                }
            }
        }

        val saveSettings: (SettingsFormState) -> Unit = { formState ->
            scope.launch {
                state = state.copy(settingsSaved = false, settingsError = null)
                val body =
                    UserSettingsBody(
                        bluesky =
                            if (
                                formState.blueskyUsername.isNotBlank() &&
                                    formState.blueskyPassword.isNotBlank()
                            )
                                BlueskySettingsBody(
                                    service =
                                        formState.blueskyService.ifBlank { "https://bsky.social" },
                                    username = formState.blueskyUsername,
                                    password = formState.blueskyPassword,
                                )
                            else null,
                        mastodon =
                            if (
                                formState.mastodonHost.isNotBlank() &&
                                    formState.mastodonToken.isNotBlank()
                            )
                                MastodonSettingsBody(
                                    host = formState.mastodonHost,
                                    accessToken = formState.mastodonToken,
                                )
                            else null,
                        twitter =
                            if (
                                formState.twitterConsumerKey.isNotBlank() &&
                                    formState.twitterConsumerSecret.isNotBlank()
                            )
                                TwitterSettingsBody(
                                    oauth1ConsumerKey = formState.twitterConsumerKey,
                                    oauth1ConsumerSecret = formState.twitterConsumerSecret,
                                )
                            else null,
                        linkedin =
                            if (
                                formState.linkedinClientId.isNotBlank() &&
                                    formState.linkedinClientSecret.isNotBlank()
                            )
                                LinkedInSettingsBody(
                                    clientId = formState.linkedinClientId,
                                    clientSecret = formState.linkedinClientSecret,
                                )
                            else null,
                        llm =
                            if (
                                formState.llmApiUrl.isNotBlank() && formState.llmApiKey.isNotBlank()
                            )
                                LlmSettingsBody(
                                    apiUrl = formState.llmApiUrl,
                                    apiKey = formState.llmApiKey,
                                    model = formState.llmModel,
                                )
                            else null,
                    )
                when (
                    val response =
                        ApiClient.put<ConfiguredServices, UserSettingsBody>(
                            "/api/account/settings",
                            body,
                        )
                ) {
                    is ApiResponse.Success -> {
                        state = state.copy(settingsSaved = true)
                        Storage.setConfiguredServices(response.data)
                    }
                    is ApiResponse.Error ->
                        state =
                            state.copy(
                                settingsError = "Failed to save settings: ${response.message}"
                            )
                    is ApiResponse.Exception ->
                        state = state.copy(settingsError = "Error: ${response.message}")
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
                placeholder = "xxxx-xxxx-xxxx-xxxx",
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
                placeholder = "Your Mastodon access token",
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
                placeholder = "OAuth1 Consumer Secret",
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
                placeholder = "LinkedIn Client Secret",
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
                placeholder = "sk-...",
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
