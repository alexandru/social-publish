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
import socialpublish.frontend.models.*
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
        var settings by remember { mutableStateOf<UserSettings?>(null) }
        var saveMessage by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(true) }
        var isSaving by remember { mutableStateOf(false) }

        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            scope.launch {
                when (val response = ApiClient.get<UserSettingsResponse>("/api/account/settings")) {
                    is ApiResponse.Success -> {
                        settings = response.data.settings ?: UserSettings()
                        isLoading = false
                    }
                    is ApiResponse.Error -> {
                        console.error("Failed to load settings: ${response.message}")
                        settings = UserSettings()
                        isLoading = false
                    }
                    is ApiResponse.Exception -> {
                        console.error("Exception loading settings: ${response.message}")
                        settings = UserSettings()
                        isLoading = false
                    }
                }
            }

            scope.launch {
                when (val response = ApiClient.get<TwitterStatusResponse>("/api/twitter/status")) {
                    is ApiResponse.Success -> {
                        val data = response.data
                        if (data.hasAuthorization) {
                            val atDateTime =
                                if (data.createdAt != null) {
                                    " at ${kotlin.js.Date(data.createdAt).toLocaleString()}"
                                } else {
                                    ""
                                }
                            twitterStatus = "Connected$atDateTime"
                        } else {
                            twitterStatus = "Not connected"
                        }

                        Storage.updateAuthStatus { current ->
                            current.copy(twitter = data.hasAuthorization)
                        }
                    }
                    is ApiResponse.Error -> {
                        twitterStatus = "Error: HTTP ${response.code}"
                    }
                    is ApiResponse.Exception -> {
                        twitterStatus = "Error: ${response.message}"
                    }
                }
            }

            scope.launch {
                when (
                    val response = ApiClient.get<LinkedInStatusResponse>("/api/linkedin/status")
                ) {
                    is ApiResponse.Success -> {
                        val data = response.data
                        if (data.hasAuthorization) {
                            val atDateTime =
                                if (data.createdAt != null) {
                                    " at ${kotlin.js.Date(data.createdAt).toLocaleString()}"
                                } else {
                                    ""
                                }
                            linkedInStatus = "Connected$atDateTime"
                        } else {
                            linkedInStatus = "Not connected"
                        }

                        Storage.updateAuthStatus { current ->
                            current.copy(linkedin = data.hasAuthorization)
                        }
                    }
                    is ApiResponse.Error -> {
                        linkedInStatus = "Error: HTTP ${response.code}"
                    }
                    is ApiResponse.Exception -> {
                        linkedInStatus = "Error: ${response.message}"
                    }
                }
            }
        }

        val authorizeTwitter: () -> Unit = { window.location.href = "/api/twitter/authorize" }
        val authorizeLinkedIn: () -> Unit = {
            scope.launch {
                when (
                    val response = ApiClient.get<LinkedInStatusResponse>("/api/linkedin/status")
                ) {
                    is ApiResponse.Success -> {
                        window.location.href = "/api/linkedin/authorize"
                    }
                    is ApiResponse.Error -> {
                        if (response.code == 503 || response.code == 500) {
                            window.alert(
                                "LinkedIn integration is not configured on the server. " +
                                    "Please configure LINKEDIN_CLIENT_ID and LINKEDIN_CLIENT_SECRET environment variables."
                            )
                        } else {
                            window.location.href = "/api/linkedin/authorize"
                        }
                    }
                    is ApiResponse.Exception -> {
                        window.alert(
                            "Could not connect to LinkedIn authorization service: ${response.message}"
                        )
                    }
                }
            }
        }

        val saveSettings: () -> Unit = {
            scope.launch {
                isSaving = true
                saveMessage = null

                settings?.let { currentSettings ->
                    when (
                        val response =
                            ApiClient.put<UserSettingsResponse, UserSettings>(
                                "/api/account/settings",
                                currentSettings,
                            )
                    ) {
                        is ApiResponse.Success -> {
                            saveMessage = "Settings saved successfully!"
                            kotlinx.coroutines.delay(3000)
                            saveMessage = null
                        }
                        is ApiResponse.Error -> {
                            saveMessage = "Error saving settings: ${response.message}"
                        }
                        is ApiResponse.Exception -> {
                            saveMessage = "Exception saving settings: ${response.message}"
                        }
                    }
                }

                isSaving = false
            }
        }

        PageContainer("account") {
            Div(attrs = { classes("block") }) {
                H1(attrs = { classes("title") }) { Text("Account Settings") }
            }

            if (!isLoading && settings != null) {
                SettingsForm(
                    settings = settings!!,
                    onSettingsChange = { newSettings -> settings = newSettings },
                    saveMessage = saveMessage,
                    isSaving = isSaving,
                    onSave = saveSettings,
                )
            } else if (isLoading) {
                Div(attrs = { classes("box", "has-text-centered") }) {
                    Span(attrs = { classes("icon", "is-large") }) {
                        I(attrs = { classes("fas", "fa-spinner", "fa-pulse") })
                    }
                    P { Text("Loading settings...") }
                }
            }

            Div(attrs = { classes("box") }) {
                H2(attrs = { classes("subtitle") }) { Text("OAuth Connections") }
                P(attrs = { classes("content", "is-small") }) {
                    Text("For Twitter and LinkedIn, you also need to authorize the app via OAuth. ")
                    Text("Configure the credentials above first, then connect here.")
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
private fun SettingsForm(
    settings: UserSettings,
    onSettingsChange: (UserSettings) -> Unit,
    saveMessage: String?,
    isSaving: Boolean,
    onSave: () -> Unit,
) {
    Div(attrs = { classes("box") }) {
        H2(attrs = { classes("subtitle") }) { Text("Integration Credentials") }
        P(attrs = { classes("content", "is-small") }) {
            Text("Configure your social media and AI service credentials. ")
            Text("These settings are stored securely and are only visible to you.")
        }

        Div(attrs = { classes("field", "is-grouped") }) {
            Div(attrs = { classes("control") }) {
                Button(
                    attrs = {
                        classes("button", "is-primary")
                        if (isSaving) classes("is-loading")
                        onClick { onSave() }
                    }
                ) {
                    Span(attrs = { classes("icon") }) { I(attrs = { classes("fas", "fa-save") }) }
                    Span { Text("Save Settings") }
                }
            }
            saveMessage?.let { msg ->
                Div(attrs = { classes("control") }) {
                    Div(
                        attrs = {
                            if (msg.contains("success", ignoreCase = true)) {
                                classes("notification", "is-success", "is-light")
                            } else {
                                classes("notification", "is-danger", "is-light")
                            }
                            style {
                                marginBottom(0.px)
                                padding(8.px, 12.px)
                            }
                        }
                    ) {
                        Text(msg)
                    }
                }
            }
        }

        Hr()

        BlueskySettingsSection(
            bluesky = settings.bluesky,
            onBlueskyChange = { newBluesky ->
                onSettingsChange(settings.copy(bluesky = newBluesky))
            },
        )

        Hr()

        MastodonSettingsSection(
            mastodon = settings.mastodon,
            onMastodonChange = { newMastodon ->
                onSettingsChange(settings.copy(mastodon = newMastodon))
            },
        )

        Hr()

        TwitterSettingsSection(
            twitter = settings.twitter,
            onTwitterChange = { newTwitter ->
                onSettingsChange(settings.copy(twitter = newTwitter))
            },
        )

        Hr()

        LinkedInSettingsSection(
            linkedin = settings.linkedin,
            onLinkedInChange = { newLinkedIn ->
                onSettingsChange(settings.copy(linkedin = newLinkedIn))
            },
        )

        Hr()

        LlmSettingsSection(
            llm = settings.llm,
            onLlmChange = { newLlm -> onSettingsChange(settings.copy(llm = newLlm)) },
        )

        Hr()

        Div(attrs = { classes("field") }) {
            Div(attrs = { classes("control") }) {
                Button(
                    attrs = {
                        classes("button", "is-primary", "is-medium")
                        if (isSaving) classes("is-loading")
                        onClick { onSave() }
                    }
                ) {
                    Span(attrs = { classes("icon") }) { I(attrs = { classes("fas", "fa-save") }) }
                    Span { Text("Save All Settings") }
                }
            }
        }
    }
}

@Composable
private fun BlueskySettingsSection(
    bluesky: BlueskyUserSettings?,
    onBlueskyChange: (BlueskyUserSettings?) -> Unit,
) {
    H3(attrs = { classes("title", "is-5") }) {
        Span(attrs = { classes("icon-text") }) {
            Span(attrs = { classes("icon", "has-text-info") }) {
                I(attrs = { classes("fas", "fa-cloud") })
            }
            Span { Text("Bluesky") }
        }
    }

    TextInputField(
        label = "Service URL",
        value = bluesky?.service ?: "https://bsky.social",
        onValueChange = { newValue ->
            onBlueskyChange(
                (bluesky
                        ?: BlueskyUserSettings(
                            service = "https://bsky.social",
                            username = "",
                            password = "",
                        ))
                    .copy(service = newValue)
            )
        },
        placeholder = "https://bsky.social",
    )

    TextInputField(
        label = "Username",
        value = bluesky?.username ?: "",
        onValueChange = { newValue ->
            onBlueskyChange(
                (bluesky
                        ?: BlueskyUserSettings(
                            service = "https://bsky.social",
                            username = "",
                            password = "",
                        ))
                    .copy(username = newValue)
            )
        },
        placeholder = "username.bsky.social",
    )

    TextInputField(
        label = "App Password",
        value = bluesky?.password ?: "",
        onValueChange = { newValue ->
            onBlueskyChange(
                (bluesky
                        ?: BlueskyUserSettings(
                            service = "https://bsky.social",
                            username = "",
                            password = "",
                        ))
                    .copy(password = newValue)
            )
        },
        type = InputType.Password,
        placeholder = "xxxx-xxxx-xxxx-xxxx",
    )
    P(attrs = { classes("help") }) { Text("Create an app password at: Settings → App Passwords") }
}

@Composable
private fun MastodonSettingsSection(
    mastodon: MastodonUserSettings?,
    onMastodonChange: (MastodonUserSettings?) -> Unit,
) {
    H3(attrs = { classes("title", "is-5") }) {
        Span(attrs = { classes("icon-text") }) {
            Span(attrs = { classes("icon", "has-text-link") }) {
                I(attrs = { classes("fab", "fa-mastodon") })
            }
            Span { Text("Mastodon") }
        }
    }

    TextInputField(
        label = "Instance Host",
        value = mastodon?.host ?: "",
        onValueChange = { newValue ->
            onMastodonChange(
                (mastodon ?: MastodonUserSettings(host = "", accessToken = "")).copy(
                    host = newValue
                )
            )
        },
        placeholder = "https://mastodon.social",
    )

    TextInputField(
        label = "Access Token",
        value = mastodon?.accessToken ?: "",
        onValueChange = { newValue ->
            onMastodonChange(
                (mastodon ?: MastodonUserSettings(host = "", accessToken = "")).copy(
                    accessToken = newValue
                )
            )
        },
        type = InputType.Password,
        placeholder = "Your access token",
    )
    P(attrs = { classes("help") }) {
        Text("Create a token at: Preferences → Development → New Application")
    }
}

@Composable
private fun TwitterSettingsSection(
    twitter: TwitterUserSettings?,
    onTwitterChange: (TwitterUserSettings?) -> Unit,
) {
    H3(attrs = { classes("title", "is-5") }) {
        Span(attrs = { classes("icon-text") }) {
            Span(attrs = { classes("icon", "has-text-info") }) {
                I(attrs = { classes("fab", "fa-x-twitter") })
            }
            Span { Text("X (Twitter)") }
        }
    }

    TextInputField(
        label = "OAuth1 Consumer Key",
        value = twitter?.oauth1ConsumerKey ?: "",
        onValueChange = { newValue ->
            onTwitterChange(
                (twitter ?: TwitterUserSettings(oauth1ConsumerKey = "", oauth1ConsumerSecret = ""))
                    .copy(oauth1ConsumerKey = newValue)
            )
        },
        placeholder = "Your consumer key",
    )

    TextInputField(
        label = "OAuth1 Consumer Secret",
        value = twitter?.oauth1ConsumerSecret ?: "",
        onValueChange = { newValue ->
            onTwitterChange(
                (twitter ?: TwitterUserSettings(oauth1ConsumerKey = "", oauth1ConsumerSecret = ""))
                    .copy(oauth1ConsumerSecret = newValue)
            )
        },
        type = InputType.Password,
        placeholder = "Your consumer secret",
    )
    P(attrs = { classes("help") }) { Text("Get credentials from Twitter Developer Portal") }
}

@Composable
private fun LinkedInSettingsSection(
    linkedin: LinkedInUserSettings?,
    onLinkedInChange: (LinkedInUserSettings?) -> Unit,
) {
    H3(attrs = { classes("title", "is-5") }) {
        Span(attrs = { classes("icon-text") }) {
            Span(attrs = { classes("icon", "has-text-link") }) {
                I(attrs = { classes("fab", "fa-linkedin") })
            }
            Span { Text("LinkedIn") }
        }
    }

    TextInputField(
        label = "Client ID",
        value = linkedin?.clientId ?: "",
        onValueChange = { newValue ->
            onLinkedInChange(
                (linkedin ?: LinkedInUserSettings(clientId = "", clientSecret = "")).copy(
                    clientId = newValue
                )
            )
        },
        placeholder = "Your LinkedIn client ID",
    )

    TextInputField(
        label = "Client Secret",
        value = linkedin?.clientSecret ?: "",
        onValueChange = { newValue ->
            onLinkedInChange(
                (linkedin ?: LinkedInUserSettings(clientId = "", clientSecret = "")).copy(
                    clientSecret = newValue
                )
            )
        },
        type = InputType.Password,
        placeholder = "Your LinkedIn client secret",
    )
    P(attrs = { classes("help") }) { Text("Get credentials from LinkedIn Developer Portal") }
}

@Composable
private fun LlmSettingsSection(llm: LlmUserSettings?, onLlmChange: (LlmUserSettings?) -> Unit) {
    H3(attrs = { classes("title", "is-5") }) {
        Span(attrs = { classes("icon-text") }) {
            Span(attrs = { classes("icon", "has-text-success") }) {
                I(attrs = { classes("fas", "fa-brain") })
            }
            Span { Text("AI / LLM Service") }
        }
    }

    TextInputField(
        label = "API URL",
        value = llm?.apiUrl ?: "",
        onValueChange = { newValue ->
            onLlmChange(
                (llm ?: LlmUserSettings(apiUrl = "", apiKey = "", model = "")).copy(
                    apiUrl = newValue
                )
            )
        },
        placeholder = "https://api.openai.com/v1/chat/completions",
    )

    TextInputField(
        label = "API Key",
        value = llm?.apiKey ?: "",
        onValueChange = { newValue ->
            onLlmChange(
                (llm ?: LlmUserSettings(apiUrl = "", apiKey = "", model = "")).copy(
                    apiKey = newValue
                )
            )
        },
        type = InputType.Password,
        placeholder = "Your API key",
    )

    TextInputField(
        label = "Model",
        value = llm?.model ?: "",
        onValueChange = { newValue ->
            onLlmChange(
                (llm ?: LlmUserSettings(apiUrl = "", apiKey = "", model = "")).copy(
                    model = newValue
                )
            )
        },
        placeholder = "gpt-4o-mini",
    )
    P(attrs = { classes("help") }) { Text("Used for generating alt-text for images") }
}
