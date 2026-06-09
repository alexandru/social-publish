package socialpublish.frontend

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.renderComposable
import socialpublish.frontend.components.NavBar
import socialpublish.frontend.pages.*
import socialpublish.frontend.utils.ApiClient
import socialpublish.frontend.utils.ApiResponse
import socialpublish.frontend.utils.ConfiguredServices
import socialpublish.frontend.utils.Storage
import socialpublish.frontend.utils.buildLoginRedirectPath
import socialpublish.frontend.utils.isUnauthorized
import socialpublish.frontend.utils.logoutAndClearLocalSession
import socialpublish.frontend.utils.navigateTo

@JsModule("bulma/css/bulma.min.css")
@JsNonModule
external val bulmaStyles: dynamic

@JsModule("@fortawesome/fontawesome-free/css/all.min.css")
@JsNonModule
external val fontAwesomeStyles: dynamic

fun main() {
    // Load bundled CSS
    bulmaStyles
    fontAwesomeStyles

    renderComposable(rootElementId = "root") { App() }
}

@Serializable
private data class SessionUserResponse(
    val username: String,
    val configuredServices: ConfiguredServices,
    val userUuid: String,
)

@Composable
fun App() {
    var currentPath by remember { mutableStateOf(window.location.pathname) }
    var sessionChecked by remember { mutableStateOf(false) }
    var userUuid by remember { mutableStateOf(Storage.getUserUuid().orEmpty()) }
    val coroutineScope = rememberCoroutineScope()

    // Handle browser navigation
    DisposableEffect(Unit) {
        val listener: (dynamic) -> Unit = {
            currentPath = window.location.pathname
        }
        window.addEventListener("popstate", listener)
        onDispose { window.removeEventListener("popstate", listener) }
    }

    LaunchedEffect(currentPath) {
        val token = Storage.getSessionToken()
        if (token == null || currentPath == "/login") {
            sessionChecked = true
            return@LaunchedEffect
        }

        sessionChecked = false
        val response = ApiClient.get<SessionUserResponse>("/api/protected")
        if (isUnauthorized(response)) {
            Storage.clearSessionToken()
            Storage.setConfiguredServices(null)
            userUuid = ""
            navigateTo(buildLoginRedirectPath(currentPath))
            return@LaunchedEffect
        }
        if (response is ApiResponse.Success) {
            Storage.setConfiguredServices(response.data.configuredServices)
            Storage.setUserUuid(response.data.userUuid)
            userUuid = response.data.userUuid
        }
        sessionChecked = true
    }

    Div {
        NavBar(currentPath = currentPath, userUuid = userUuid) {
            coroutineScope.launch {
                logoutAndClearLocalSession()
                userUuid = ""
                navigateTo("/login")
            }
        }

        Main {
            if (!sessionChecked && currentPath != "/login") {
                Div {}
            } else {
                when (currentPath) {
                    "/" -> RedirectToForm()
                    "/login" -> LoginPage()
                    "/form" -> PublishFormPage(userUuid = userUuid)
                    "/account" -> AccountPage()
                    else -> NotFoundPage()
                }
            }
        }
    }
}

@Composable
private fun RedirectToForm() {
    LaunchedEffect(Unit) { navigateTo("/form") }
}
