package socialpublish.frontend.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.events.KeyboardEvent

enum class MessageType(val title: String, val cssClass: String) {
    INFO("Info", "is-info"),
    WARNING("Warning", "is-warning"),
    ERROR("Error", "is-danger"),
}

@Composable
fun ModalMessage(
    type: MessageType,
    isEnabled: Boolean,
    onDisable: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Handle Escape key
    DisposableEffect(isEnabled) {
        if (isEnabled) {
            val handleKeyDown: (dynamic) -> Unit = { event ->
                val keyboardEvent = event as? KeyboardEvent
                if (keyboardEvent?.key == "Escape" || keyboardEvent?.keyCode == 27) {
                    onDisable()
                }
            }
            window.addEventListener("keydown", handleKeyDown)
            onDispose { window.removeEventListener("keydown", handleKeyDown) }
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
                Article(attrs = { classes("message", "is-medium", type.cssClass) }) {
                    Div(attrs = { classes("message-header") }) {
                        P { Text(type.title) }
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
