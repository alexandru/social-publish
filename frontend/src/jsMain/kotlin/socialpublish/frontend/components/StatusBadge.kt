package socialpublish.frontend.components

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.I
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Small status pill rendered on the right side of a collapsible section's
 * header. Uses the same `tag is-medium is-rounded` pattern as
 * `PublishTargetCheckbox` for visual consistency with the rest of the app.
 */
@Composable
fun StatusBadge(isConfigured: Boolean, configuredLabel: String = "Configured") {
    if (isConfigured) {
        Span(
            attrs = { classes("tag", "is-success", "is-light", "is-rounded") }
        ) {
            I(attrs = { classes("fas", "fa-check", "mr-1") })
            Text(configuredLabel)
        }
    } else {
        Span(
            attrs = {
                classes("tag", "is-light", "is-rounded", "has-text-grey")
            }
        ) {
            I(attrs = { classes("fas", "fa-circle-notch", "mr-1") })
            Text("Not configured")
        }
    }
}
