package socialpublish.frontend.pages

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.components.ErrorModal
import socialpublish.frontend.components.PageContainer
import socialpublish.frontend.components.TextInputField
import socialpublish.frontend.models.LoginRequest
import socialpublish.frontend.models.LoginResponse
import socialpublish.frontend.utils.ApiClient
import socialpublish.frontend.utils.ApiResponse
import socialpublish.frontend.utils.Storage
import socialpublish.frontend.utils.navigateTo

// External declaration for URLSearchParams
external class URLSearchParams(init: String = definedExternally) {
    fun get(name: String): String?
}

@Composable
fun LoginPage() {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Check for redirect query param and validate it's a safe internal path
    val redirectParam = remember {
        val redirect = URLSearchParams(window.location.search).get("redirect")
        // Only allow internal paths that start with '/' and don't contain '..'
        if (redirect != null && redirect.startsWith("/") && !redirect.contains("..")) {
            redirect
        } else {
            null
        }
    }

    val handleSubmit: () -> Unit = {
        scope.launch {
            isLoading = true
            try {
                val response =
                    ApiClient.post<LoginResponse, LoginRequest>(
                        "/api/login",
                        LoginRequest(username, password),
                    )

                when (response) {
                    is ApiResponse.Success -> {
                        Storage.setJwtToken(response.data.token)
                        Storage.setAuthStatus(response.data.hasAuth)
                        Storage.setConfiguredServices(response.data.configuredServices)
                        navigateTo(redirectParam ?: "/form")
                    }
                    is ApiResponse.Error -> {
                        error = response.message
                    }
                    is ApiResponse.Exception -> {
                        error = "Exception while logging in: ${response.message}"
                    }
                }
            } catch (e: Exception) {
                error = "Exception while logging in: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    ErrorModal(message = error) { error = null }

    PageContainer("login") {
        H1(attrs = { classes("title") }) { Text("Login") }

        // Show info notification if redirected from a protected page
        if (redirectParam != null) {
            Div(attrs = { classes("notification", "is-info", "is-light") }) {
                Text("You need to log in to access this page.")
            }
        }

        Form(
            attrs = {
                classes("box")
                addEventListener("submit") { event ->
                    event.preventDefault()
                    handleSubmit()
                }
            }
        ) {
            TextInputField(
                label = "Username",
                value = username,
                onValueChange = { username = it },
                required = true,
                disabled = isLoading,
            )

            TextInputField(
                label = "Password",
                value = password,
                onValueChange = { password = it },
                type = InputType.Password,
                required = true,
                disabled = isLoading,
            )

            Button(
                attrs = {
                    classes("button", "is-primary")
                    attr("type", "submit")
                    if (isLoading) {
                        classes("is-loading")
                        attr("disabled", "")
                    }
                }
            ) {
                Text("Submit")
            }
        }
    }
}
