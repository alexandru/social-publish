package socialpublish.frontend.pages

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.components.Authorize
import socialpublish.frontend.components.PageContainer
import socialpublish.frontend.components.TextInputField
import socialpublish.frontend.models.BlueskyUserSettings
import socialpublish.frontend.models.ConfiguredServices
import socialpublish.frontend.models.LinkedInUserSettings
import socialpublish.frontend.models.LlmUserSettings
import socialpublish.frontend.models.MastodonUserSettings
import socialpublish.frontend.models.TwitterUserSettings
import socialpublish.frontend.models.UserSettings
import socialpublish.frontend.utils.ApiClient
import socialpublish.frontend.utils.ApiResponse
import socialpublish.frontend.utils.Storage

@Serializable
data class TwitterStatusResponse(val hasAuthorization: Boolean, val createdAt: Long? = null)

@Serializable
data class LinkedInStatusResponse(val hasAuthorization: Boolean, val createdAt: Long? = null)

@Composable
fun AccountPage() {
    Authorize {
        var twitterStatus by remember { mutableStateOf("Querying...") }
        var linkedInStatus by remember { mutableStateOf("Querying...") }
        var userSettings by remember { mutableStateOf<UserSettings?>(null) }
        var settingsSaved by remember { mutableStateOf(false) }
        var settingsError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            // Load user settings
            scope.launch {
                when (val response = ApiClient.get<UserSettings>("/api/account/settings")) {
                    is ApiResponse.Success -> {
                        userSettings = response.data
                        // Update configured services in storage
                        Storage.setConfiguredServices(
                            ConfiguredServices(
                                mastodon = response.data.mastodon != null,
                                bluesky = response.data.bluesky != null,
                                twitter = response.data.twitter != null,
                                linkedin = response.data.linkedin != null,
                                llm = response.data.llm != null,
                            )
                        )
                    }
                    is ApiResponse.Error ->
                        settingsError = "Failed to load settings: ${response.message}"
                    is ApiResponse.Exception -> settingsError = "Error: ${response.message}"
                }
            }

            scope.launch {
                when (val response = ApiClient.get<TwitterStatusResponse>("/api/twitter/status")) {
                    is ApiResponse.Success -> {
                        val data = response.data
                        twitterStatus =
                            if (data.hasAuthorization) {
                                "Connected" +
                                    (data.createdAt?.let {
                                        " at ${kotlin.js.Date(it).toLocaleString()}"
                                    } ?: "")
                            } else {
                                "Not connected"
                            }
                        Storage.updateAuthStatus { current ->
                            current.copy(twitter = data.hasAuthorization)
                        }
                    }
                    is ApiResponse.Error -> twitterStatus = "Error: HTTP ${response.code}"
                    is ApiResponse.Exception -> twitterStatus = "Error: ${response.message}"
                }
            }

            scope.launch {
                when (
                    val response = ApiClient.get<LinkedInStatusResponse>("/api/linkedin/status")
                ) {
                    is ApiResponse.Success -> {
                        val data = response.data
                        linkedInStatus =
                            if (data.hasAuthorization) {
                                "Connected" +
                                    (data.createdAt?.let {
                                        " at ${kotlin.js.Date(it).toLocaleString()}"
                                    } ?: "")
                            } else {
                                "Not connected"
                            }
                        Storage.updateAuthStatus { current ->
                            current.copy(linkedin = data.hasAuthorization)
                        }
                    }
                    is ApiResponse.Error -> linkedInStatus = "Error: HTTP ${response.code}"
                    is ApiResponse.Exception -> linkedInStatus = "Error: ${response.message}"
                }
            }
        }

        val saveSettings: (UserSettings) -> Unit = { settings ->
            scope.launch {
                settingsSaved = false
                settingsError = null
                when (
                    val response =
                        ApiClient.put<UserSettings, UserSettings>("/api/account/settings", settings)
                ) {
                    is ApiResponse.Success -> {
                        userSettings = response.data
                        settingsSaved = true
                        // Update configured services in storage after save
                        Storage.setConfiguredServices(
                            ConfiguredServices(
                                mastodon = response.data.mastodon != null,
                                bluesky = response.data.bluesky != null,
                                twitter = response.data.twitter != null,
                                linkedin = response.data.linkedin != null,
                                llm = response.data.llm != null,
                            )
                        )
                    }
                    is ApiResponse.Error ->
                        settingsError = "Failed to save settings: ${response.message}"
                    is ApiResponse.Exception -> settingsError = "Error: ${response.message}"
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

            if (settingsSaved) {
                Div(attrs = { classes("notification", "is-success", "is-light") }) {
                    Text("Settings saved successfully!")
                }
            }
            if (settingsError != null) {
                Div(attrs = { classes("notification", "is-danger", "is-light") }) {
                    Text(settingsError ?: "")
                }
            }

            // Social network credentials form
            val currentSettings = userSettings ?: UserSettings()
            SettingsForm(settings = currentSettings, onSave = saveSettings)

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
                P(attrs = { classes("help") }) { Text(twitterStatus) }

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
                P(attrs = { classes("help") }) { Text(linkedInStatus) }
            }
        }
    }
}

@Composable
private fun SettingsForm(settings: UserSettings, onSave: (UserSettings) -> Unit) {
    var blueskyService by
        remember(settings) { mutableStateOf(settings.bluesky?.service ?: "https://bsky.social") }
    var blueskyUsername by remember(settings) { mutableStateOf(settings.bluesky?.username ?: "") }
    var blueskyPassword by remember(settings) { mutableStateOf(settings.bluesky?.password ?: "") }

    var mastodonHost by remember(settings) { mutableStateOf(settings.mastodon?.host ?: "") }
    var mastodonToken by remember(settings) { mutableStateOf(settings.mastodon?.accessToken ?: "") }

    var twitterConsumerKey by
        remember(settings) { mutableStateOf(settings.twitter?.oauth1ConsumerKey ?: "") }
    var twitterConsumerSecret by
        remember(settings) { mutableStateOf(settings.twitter?.oauth1ConsumerSecret ?: "") }

    var linkedinClientId by remember(settings) { mutableStateOf(settings.linkedin?.clientId ?: "") }
    var linkedinClientSecret by
        remember(settings) { mutableStateOf(settings.linkedin?.clientSecret ?: "") }

    var llmApiUrl by remember(settings) { mutableStateOf(settings.llm?.apiUrl ?: "") }
    var llmApiKey by remember(settings) { mutableStateOf(settings.llm?.apiKey ?: "") }
    var llmModel by remember(settings) { mutableStateOf(settings.llm?.model ?: "") }

    val handleSave: () -> Unit = {
        val newSettings =
            UserSettings(
                bluesky =
                    if (blueskyUsername.isNotBlank() && blueskyPassword.isNotBlank())
                        BlueskyUserSettings(
                            service = blueskyService.ifBlank { "https://bsky.social" },
                            username = blueskyUsername,
                            password = blueskyPassword,
                        )
                    else null,
                mastodon =
                    if (mastodonHost.isNotBlank() && mastodonToken.isNotBlank())
                        MastodonUserSettings(host = mastodonHost, accessToken = mastodonToken)
                    else null,
                twitter =
                    if (twitterConsumerKey.isNotBlank() && twitterConsumerSecret.isNotBlank())
                        TwitterUserSettings(
                            oauth1ConsumerKey = twitterConsumerKey,
                            oauth1ConsumerSecret = twitterConsumerSecret,
                        )
                    else null,
                linkedin =
                    if (linkedinClientId.isNotBlank() && linkedinClientSecret.isNotBlank())
                        LinkedInUserSettings(
                            clientId = linkedinClientId,
                            clientSecret = linkedinClientSecret,
                        )
                    else null,
                llm =
                    if (llmApiUrl.isNotBlank() && llmApiKey.isNotBlank())
                        LlmUserSettings(apiUrl = llmApiUrl, apiKey = llmApiKey, model = llmModel)
                    else null,
            )
        onSave(newSettings)
    }

    Form(
        attrs = {
            addEventListener("submit") { event ->
                event.preventDefault()
                handleSave()
            }
        }
    ) {
        // Bluesky
        Div(attrs = { classes("box", "mb-4") }) {
            H2(attrs = { classes("subtitle") }) { Text("Bluesky") }
            TextInputField(
                label = "Service URL",
                value = blueskyService,
                onValueChange = { blueskyService = it },
                placeholder = "https://bsky.social",
            )
            TextInputField(
                label = "Username",
                value = blueskyUsername,
                onValueChange = { blueskyUsername = it },
                placeholder = "user.bsky.social",
            )
            TextInputField(
                label = "App Password",
                value = blueskyPassword,
                onValueChange = { blueskyPassword = it },
                placeholder = "xxxx-xxxx-xxxx-xxxx",
            )
        }

        // Mastodon
        Div(attrs = { classes("box", "mb-4") }) {
            H2(attrs = { classes("subtitle") }) { Text("Mastodon") }
            TextInputField(
                label = "Host URL",
                value = mastodonHost,
                onValueChange = { mastodonHost = it },
                placeholder = "https://mastodon.social",
            )
            TextInputField(
                label = "Access Token",
                value = mastodonToken,
                onValueChange = { mastodonToken = it },
                placeholder = "Your Mastodon access token",
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
                value = twitterConsumerKey,
                onValueChange = { twitterConsumerKey = it },
                placeholder = "OAuth1 Consumer Key",
            )
            TextInputField(
                label = "Consumer Secret",
                value = twitterConsumerSecret,
                onValueChange = { twitterConsumerSecret = it },
                placeholder = "OAuth1 Consumer Secret",
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
                value = linkedinClientId,
                onValueChange = { linkedinClientId = it },
                placeholder = "LinkedIn Client ID",
            )
            TextInputField(
                label = "Client Secret",
                value = linkedinClientSecret,
                onValueChange = { linkedinClientSecret = it },
                placeholder = "LinkedIn Client Secret",
            )
        }

        // LLM
        Div(attrs = { classes("box", "mb-4") }) {
            H2(attrs = { classes("subtitle") }) { Text("LLM (AI alt-text generation)") }
            TextInputField(
                label = "API URL",
                value = llmApiUrl,
                onValueChange = { llmApiUrl = it },
                placeholder = "https://api.openai.com/v1/chat/completions",
            )
            TextInputField(
                label = "API Key",
                value = llmApiKey,
                onValueChange = { llmApiKey = it },
                placeholder = "sk-...",
            )
            TextInputField(
                label = "Model",
                value = llmModel,
                onValueChange = { llmModel = it },
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
