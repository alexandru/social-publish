package socialpublish.frontend.pages

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.components.PageContainer

@Composable
fun HomePage() {
    PageContainer("home") {
        Div(attrs = { classes("box") }) {
            Ul(attrs = { classes("content", "is-medium") }) {
                Li { A(href = "/rss", attrs = { attr("target", "_blank") }) { Text("RSS") } }
                Li {
                    A(
                        href = "https://github.com/alexandru/social-publish",
                        attrs = {
                            attr("target", "_blank")
                            attr("rel", "noreferrer")
                        },
                    ) {
                        Text("GitHub")
                    }
                }
            }
        }
    }
}
