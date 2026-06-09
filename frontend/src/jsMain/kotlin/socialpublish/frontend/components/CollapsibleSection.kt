package socialpublish.frontend.components

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.I
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/**
 * A collapsible section backed by the native HTML `<details>` / `<summary>`
 * elements. Open/closed state lives in the DOM (browser-managed), so the
 * composable itself is stateless and unaffected by recomposition.
 *
 * The header shows an icon, a title, and an optional [summary] slot on the
 * right (e.g. a status badge).
 *
 * @param title Section title shown in the header.
 * @param icon Font Awesome icon class fragment (e.g. "fa-bluesky",
 *   "fa-share-nodes").
 * @param iconPrefix Font Awesome family prefix for [icon]. Use `"fas"` for
 *   solid icons (the default) and `"fab"` for brand icons (e.g. social
 *   networks). Mixing solid prefix with a brand icon name renders as a
 *   broken-glyph box, so this must match the icon's font family.
 * @param defaultOpen Whether the section starts open. Defaults to `false` so
 *   sections are collapsed by default.
 * @param summary Optional content shown on the right side of the header,
 *   typically a status badge.
 * @param content Body of the section, shown when expanded.
 */
@Composable
fun CollapsibleSection(
    title: String,
    icon: String,
    iconPrefix: String = "fas",
    defaultOpen: Boolean = false,
    summary: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    TagElement<HTMLElement>(
        tagName = "details",
        applyAttrs = {
            classes("box", "mb-4", "collapsible-section")
            if (defaultOpen) attr("open", "")
        },
    ) {
        TagElement<HTMLElement>(
            tagName = "summary",
            applyAttrs = {
                classes(
                    "is-flex",
                    "is-align-items-center",
                    "is-justify-content-space-between",
                    "p-0",
                )
            },
        ) {
            Span(
                attrs = {
                    classes(
                        "is-flex",
                        "is-align-items-center",
                        "is-flex-grow-1",
                    )
                }
            ) {
                Span(attrs = { classes("icon", "mr-2", "has-text-link") }) {
                    I(attrs = { classes(iconPrefix, icon) })
                }
                Span(attrs = { classes("title", "is-6", "mb-0") }) {
                    Text(title)
                }
            }
            if (summary != null) {
                Span(attrs = { classes("ml-3") }) { summary() }
            }
        }
        Span(attrs = { classes("mt-3", "is-block") }) { content() }
    }
}
