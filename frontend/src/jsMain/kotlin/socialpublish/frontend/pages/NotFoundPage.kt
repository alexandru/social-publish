package socialpublish.frontend.pages

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.components.PageContainer

@Composable
fun NotFoundPage() {
    PageContainer("notFound") {
        H1(attrs = { classes("title") }) { Text("404: Not Found") }
        P(attrs = { classes("subtitle") }) { Text("It's gone 😞") }
    }
}
