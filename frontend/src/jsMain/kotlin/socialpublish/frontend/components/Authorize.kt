package socialpublish.frontend.components

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.utils.Storage

@Composable
fun Authorize(content: @Composable () -> Unit) {
    val token = Storage.getJwtToken()

    if (token == null) {
        LaunchedEffect(Unit) {
            val redirect = window.location.pathname
            window.location.href = "/login?redirect=$redirect"
        }
    } else {
        Div(attrs = { classes("authorized") }) { content() }
    }
}
