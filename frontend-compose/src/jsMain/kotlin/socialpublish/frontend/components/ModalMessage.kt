package socialpublish.frontend.components

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.events.KeyboardEvent

enum class MessageType {
    INFO,
    WARNING,
    ERROR,
}

@Composable
fun ModalMessage(
    type: MessageType,
    isEnabled: Boolean,
    onDisable: () -> Unit,
    content: @Composable () -> Unit,
) {
    val title =
        when (type) {
            MessageType.INFO -> "Info"
            MessageType.WARNING -> "Warning"
            MessageType.ERROR -> "Error"
        }

    val cssClass =
        when (type) {
            MessageType.INFO -> "is-info"
            MessageType.WARNING -> "is-warning"
            MessageType.ERROR -> "is-danger"
        }

    // Handle Escape key
    DisposableEffect(isEnabled) {
        if (isEnabled) {
            val handleKeyDown: (dynamic) -> Unit = { event ->
                val keyboardEvent = event as? KeyboardEvent
                if (keyboardEvent?.key == "Escape" || keyboardEvent?.keyCode == 27) {
                    onDisable()
                }
            }
            kotlinx.browser.window.addEventListener("keydown", handleKeyDown)
            onDispose { kotlinx.browser.window.removeEventListener("keydown", handleKeyDown) }
        } else {
            onDispose {}
        }
    }

    if (isEnabled) {
        Div(
            attrs = {
                classes("modal", "is-active")
                id("message-modal")
            }
        ) {
            Div(
                attrs = {
                    classes("modal-background")
                    onClick { onDisable() }
                }
            )

            Div(attrs = { classes("modal-content") }) {
                Article(attrs = { classes("message", "is-medium", cssClass) }) {
                    Div(attrs = { classes("message-header") }) {
                        P { Text(title) }
                        Button(
                            attrs = {
                                classes("delete")
                                attr("aria-label", "delete")
                                onClick { onDisable() }
                            }
                        )
                    }
                    Div(attrs = { classes("message-body") }) { content() }
                }
            }

            Button(
                attrs = {
                    classes("modal-close", "is-large")
                    attr("aria-label", "close")
                    onClick { onDisable() }
                }
            )
        }
    }
}
