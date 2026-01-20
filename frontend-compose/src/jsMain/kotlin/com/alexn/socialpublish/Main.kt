package com.alexn.socialpublish

import androidx.compose.runtime.*
import com.alexn.socialpublish.components.NavBar
import com.alexn.socialpublish.pages.HomePage
import com.alexn.socialpublish.pages.LoginPage
import com.alexn.socialpublish.utils.Storage
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

    renderComposable(rootElementId = "root") {
        App()
    }
}

@Composable
fun App() {
    var currentPath by remember { mutableStateOf(window.location.pathname) }

    // Handle browser navigation
    DisposableEffect(Unit) {
        val listener: (dynamic) -> Unit = {
            currentPath = window.location.pathname
        }
        window.addEventListener("popstate", listener)
        onDispose {
            window.removeEventListener("popstate", listener)
        }
    }

    Div {
        NavBar(currentPath = currentPath) {
            Storage.clearJwtToken()
            window.location.href = "/"
        }

        when (currentPath) {
            "/" -> HomePage()
            "/login" -> LoginPage()
            else -> {
                Section(attrs = { classes("section") }) {
                    Div(attrs = { classes("container") }) {
                        H1(attrs = { classes("title") }) {
                            Text("404 - Page Not Found")
                        }
                        P {
                            Text("The page you're looking for doesn't exist.")
                        }
                    }
                }
            }
        }
    }
}
