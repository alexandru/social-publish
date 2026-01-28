package socialpublish.frontend.pages

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.components.Authorize
import socialpublish.frontend.components.PageContainer
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
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
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
            // Check if LinkedIn is configured before redirecting
            scope.launch {
                when (
                    val response = ApiClient.get<LinkedInStatusResponse>("/api/linkedin/status")
                ) {
                    is ApiResponse.Success -> {
                        // If we get a successful response, proceed with authorization
                        window.location.href = "/api/linkedin/authorize"
                    }
                    is ApiResponse.Error -> {
                        // If status returns error, the integration may not be configured
                        if (response.code == 503 || response.code == 500) {
                            window.alert(
                                "LinkedIn integration is not configured on the server. " +
                                    "Please configure LINKEDIN_CLIENT_ID and LINKEDIN_CLIENT_SECRET environment variables."
                            )
                        } else {
                            // Other errors, try to proceed anyway
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

        PageContainer("account") {
            Div(attrs = { classes("block") }) {
                H1(attrs = { classes("title") }) { Text("Account Settings") }
            }

            Div(attrs = { classes("box") }) {
                H2(attrs = { classes("subtitle") }) { Text("Social Accounts") }
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
