package socialpublish.frontend.components

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

enum class NotificationType {
    SUCCESS,
    INFO,
    WARNING,
    ERROR,
}

@Composable
fun NotificationMessage(
    message: String,
    type: NotificationType,
    onDismiss: (() -> Unit)? = null,
) {
    Div(
        attrs = {
            classes(
                "notification",
                when (type) {
                    NotificationType.SUCCESS -> "is-success"
                    NotificationType.INFO -> "is-info"
                    NotificationType.WARNING -> "is-warning"
                    NotificationType.ERROR -> "is-danger"
                },
                "is-light",
            )
        }
    ) {
        if (onDismiss != null) {
            Button(
                attrs = {
                    classes("delete")
                    onClick { onDismiss() }
                }
            )
        }
        Text(message)
    }
}
