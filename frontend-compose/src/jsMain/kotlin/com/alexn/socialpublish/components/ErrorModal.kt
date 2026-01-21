package com.alexn.socialpublish.components

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun ErrorModal(message: String?, onClose: () -> Unit) {
    ModalMessage(type = MessageType.ERROR, isEnabled = message != null, onDisable = onClose) {
        Text(message ?: "")
    }
}
