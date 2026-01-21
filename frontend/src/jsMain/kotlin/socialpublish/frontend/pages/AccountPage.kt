package socialpublish.frontend.pages

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.components.Authorize
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
        val authorizeLinkedIn: () -> Unit = { window.location.href = "/api/linkedin/authorize" }

        Div(
            attrs = {
                classes("account")
                id("account")
            }
        ) {
            Section(attrs = { classes("section") }) {
                Div(attrs = { classes("container", "block") }) {
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
                            Img(
                                src =
                                    "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI1MTIiIGhlaWdodD0iNTEyIiB2aWV3Qm94PSIwIDAgNTEyIDUxMiI+PHBhdGggZD0iTTQ5Niw0MDkuMWMtMi0yNy0zLjgtNTYuNy05LjgtODEuNy0zLjctMTUuNS05LjEtMzAuMi0xOC4zLTQzLjNjLTguMS0xMS41LTE4LjYtMjAuNC0zMS4zLTI2LjNjLTE0LjMtNi42LTI5LjctOS44LTQ1LjMtMTAuNWMtMzEuMy0xLjQtNjIuNywwLjgtOTQuMS0xLjFjLTEyLjEtMC43LTIxLjItNi41LTI4LjgtMTUuNmMtMTguNC0yMi4yLTM5LjktNDEuMi02MC41LTYwLjljLTEwLjItOS42LTE5LjUtMTkuNy0yOS41LTI5LjRjLTEwLjYtMTAuMi0yMi45LTE1LjctMzguMy0xNWMtMzEuNywxLjUtNjMuNCwwLjUtOTUuMSwwLjZjLTEwLjksMC0xNi45LDYuMy0xNi40LDE2LjljMS42LDMxLjcsMS42LDYzLjQsMCw5NS4xYy0wLjUsMTAuNyw2LDE3LDE3LDE2LjljMzEuNy0wLjcsNjMuNC0wLjMsOTUuMS0wLjFjOC44LDAuMSwxNi45LDIuNCwyNC4yLDguNWMxLjIsMSwyLjQsMS44LDMuNiwyLjdjMTguNywxNS4xLDM3LjgsMjkuNyw1Ni40LDQ1YzcuNiw2LjMsMTQuNiwxMy40LDIxLjEsMjEuNGM5LjQsMTEuNywxNC4zLDI0LjksMTMuNyw0MC42Yy0xLDE0LjYtMC4xLDI5LjMtMC4zLDQzLjljMCwzLjIsMCw2LjQsMCw5LjdjLTIwLjEtMTUuNi0zOS00MC44LTU5LjUtNjIuM2MtMjAuNC0yMS40LTQyLjgtMzkuNy02Ny40LTU1LjZjLTE5LjQtMTIuNi00MC4xLTIyLjMtNjIuOS0yNi43Yy0yNy4zLTUuMy01NC4yLTMuNi04MC43LDUuNWMtNDEuNywxNC4zLTc1LjcsNDAuMi0xMDEuMSw3NS45Yy0xNy4yLDI0LjItMjkuMyw1MC44LTM2LjQsNzkuOGMtMTMuOSw1Ny4xLTYuNywxMTEuMSwyMy4xLDE2MS4zQzM5LjgsNDczLjMsNzIuMSw0OTcsMTExLDUxMS41YzMxLjcsMTEuOSw2NC41LDE0LjgsOTcuOCwxMS4yYzMzLjItMy42LDY0LjktMTMuOCw5NC44LTMwLjVjMzEuOC0xNy43LDU5LjItNDEuMSw4Mi43LTY4LjhjMTkuOC0yMy4zLDM2LjUtNDguNyw0OS41LTc2LjZjMC0wLjEsMC4xLTAuMiwwLjEtMC4zYzIuNiwyLjQsNS4yLDQuNyw3LjcsNy4xYzE0LjYsMTMuOSwyOS4zLDI3LjksNDQuMSw0MS42YzYuOSw2LjQsMTQuNiwxMS45LDI0LjMsMTQuMmMxNi41LDMuOSwzMy4yLDQuOSw1MC4xLDQuN2MyOC4zLTAuMyw1Ni42LTAuMSw4NC45LTAuM2M2LjYsMCwxMi43LTEuNywxOC41LTUuMmM3LjYtNC41LDEyLjEtMTEsOS44LTIwLjNDNDk5LjYsNDE2LjksNDk3LjgsNDEzLDQ5Niw0MDkuMXogTTE3My40LDQyMS44Yy04LDE1LjUtMTguNywyOS4zLTMxLjQsNDEuNWMtMTkuNSwxOC44LTQxLjcsMzMuMy02Ny4yLDQyLjdjLTI0LjUsOC45LTQ5LjUsMTIuNi03NS43LDguMWMtMjMuMi0zLjktNDQuNC0xMi41LTYzLjItMjYuN2MtMjEuNi0xNi4yLTM3LjQtMzYuOC00Ny40LTYxLjdjLTExLjYtMjguOS0xMy45LTU4LjktOC4zLTg5LjRjNC43LTI1LjksMTQuMy00OS44LDI5LjctNzEuMWMxNi03MS4yLDMzLjctNDcuNiw3NC45LTU5LjJjMTAuMi0yLjksMjAuNi00LjIsMzEuMi00LjJjMTYuOCwwLDMzLjEsMy45LDQ4LjQsMTAuOWMzMC45LDE0LDU4LjMsMzMuNyw4Mi4xLDU3LjdjMjMuMSwyMy4yLDQzLjIsNDguNyw1OC4yLDc3LjdjMC41LDAuOSwwLjksMS45LDEuNCwyLjljLTEzLjcsNi45LTI3LjEsMTQuNC0zNy4yLDI2LjljLTcuNiw5LjQtMTIuOSwxOS44LTE0LjUsMzEuOEMxNzQuMSw0MDguOSwxNzMuNyw0MTUuNCwxNzMuNCw0MjEuOHoiLz48L3N2Zz4=",
                                attrs = { style { property("filter", "invert(1)") } },
                            )
                        }
                        Span(attrs = { style { fontWeight("bold") } }) {
                            Text("Connect X (Twitter)")
                        }
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
                            I(attrs = { classes("fa", "fa-linkedin") })
                        }
                        Span(attrs = { style { fontWeight("bold") } }) { Text("Connect LinkedIn") }
                    }
                    P(attrs = { classes("help") }) { Text(linkedInStatus) }
                }
            }
        }
    }
}
