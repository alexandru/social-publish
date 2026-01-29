package socialpublish.frontend

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.renderComposable
import socialpublish.frontend.components.NavBar
import socialpublish.frontend.pages.*
import socialpublish.frontend.utils.Storage
import socialpublish.frontend.utils.navigateTo

@JsModule("bulma/css/bulma.min.css") @JsNonModule external val bulmaStyles: dynamic

@JsModule("@fortawesome/fontawesome-free/css/all.min.css")
@JsNonModule
external val fontAwesomeStyles: dynamic

fun main() {
    // Load bundled CSS
    bulmaStyles
    fontAwesomeStyles

    renderComposable(rootElementId = "root") { App() }
}

@Composable
fun App() {
    var currentPath by remember { mutableStateOf(window.location.pathname) }

    // Handle browser navigation
    DisposableEffect(Unit) {
        val listener: (dynamic) -> Unit = { currentPath = window.location.pathname }
        window.addEventListener("popstate", listener)
        onDispose { window.removeEventListener("popstate", listener) }
    }

    Div {
        NavBar(currentPath = currentPath) {
            Storage.clearJwtToken()
            Storage.setAuthStatus(null)
            window.location.href = "/"
        }

        Main {
            when (currentPath) {
                "/" -> RedirectToForm()
                "/login" -> LoginPage()
                "/form" -> PublishFormPage()
                "/account" -> AccountPage()
                else -> NotFoundPage()
            }
        }
    }
}

@Composable
private fun RedirectToForm() {
    LaunchedEffect(Unit) { navigateTo("/form") }
}
