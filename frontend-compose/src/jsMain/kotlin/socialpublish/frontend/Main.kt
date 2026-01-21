package socialpublish.frontend

import androidx.compose.runtime.*
import socialpublish.frontend.components.NavBar
import socialpublish.frontend.pages.*
import socialpublish.frontend.utils.Storage
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLLinkElement

fun main() {
    // Load Bulma CSS
    val link = document.createElement("link") as HTMLLinkElement
    link.rel = "stylesheet"
    link.href = "https://cdn.jsdelivr.net/npm/bulma@1.0.4/css/bulma.min.css"
    document.head?.appendChild(link)

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
                "/" -> HomePage()
                "/login" -> LoginPage()
                "/form" -> PublishFormPage()
                "/account" -> AccountPage()
                else -> NotFoundPage()
            }
        }
    }
}
