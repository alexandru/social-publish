package socialpublish.frontend.components

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Text

@Composable
fun ErrorModal(message: String?, onClose: () -> Unit) {
    ModalMessage(type = MessageType.ERROR, isEnabled = message != null, onDisable = onClose) {
        Text(message ?: "")
    }
}
