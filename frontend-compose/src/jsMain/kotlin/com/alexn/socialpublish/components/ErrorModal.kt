package com.alexn.socialpublish.components

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun ErrorModal(
    message: String?,
    onClose: () -> Unit
) {
    if (message != null) {
        Div(attrs = {
            classes("modal", "is-active")
        }) {
            Div(attrs = {
                classes("modal-background")
                onClick { onClose() }
            })

            Div(attrs = { classes("modal-content") }) {
                Div(attrs = { classes("box") }) {
                    Article(attrs = { classes("message", "is-danger") }) {
                        Div(attrs = { classes("message-header") }) {
                            P { Text("Error") }
                            Button(attrs = {
                                classes("delete")
                                attr("aria-label", "delete")
                                onClick { onClose() }
                            })
                        }
                        Div(attrs = { classes("message-body") }) {
                            Text(message)
                        }
                    }
                }
            }

            Button(attrs = {
                classes("modal-close", "is-large")
                attr("aria-label", "close")
                onClick { onClose() }
            })
        }
    }
}
