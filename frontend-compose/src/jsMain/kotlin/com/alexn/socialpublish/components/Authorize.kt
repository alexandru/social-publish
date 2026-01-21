package com.alexn.socialpublish.components

import androidx.compose.runtime.*
import com.alexn.socialpublish.utils.Storage
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun Authorize(content: @Composable () -> Unit) {
    var showError by remember { mutableStateOf(false) }
    val token = Storage.getJwtToken()

    if (token == null) {
        LaunchedEffect(Unit) { showError = true }

        ModalMessage(
            type = MessageType.ERROR,
            isEnabled = showError,
            onDisable = {
                showError = false
                val redirect = window.location.pathname
                window.location.href = "/login?redirect=$redirect"
            },
        ) {
            Text("You are not authorized to view this page. Please log in...")
        }
    } else {
        Div(attrs = { classes("authorized") }) { content() }
    }
}
