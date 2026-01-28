package socialpublish.frontend.components

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Section

@Composable
fun PageContainer(pageClass: String, content: @Composable () -> Unit) {
    Div(attrs = { classes(pageClass) }) {
        Section(attrs = { classes("section") }) {
            Div(attrs = { classes("container", "is-max-desktop") }) { content() }
        }
    }
}
