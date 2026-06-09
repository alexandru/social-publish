package socialpublish.frontend.components

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Hr
import org.jetbrains.compose.web.dom.I
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/**
 * A labeled horizontal rule used to mark the transition between two distinct
 * concerns on a page (e.g. configuration vs. authorization). Renders as `[line]
 * ◆ Label [line]`.
 *
 * Built only from Bulma layout / color / icon primitives — no ad-hoc CSS.
 *
 * @param label Short label shown in the middle of the rule.
 * @param icon Font Awesome icon class fragment (e.g. "fa-plug").
 * @param iconPrefix Font Awesome family prefix for [icon] (`"fas"` for solid,
 *   `"fab"` for brand).
 */
@Composable
fun SectionDivider(label: String, icon: String, iconPrefix: String = "fas") {
    TagElement<HTMLElement>(
        tagName = "div",
        applyAttrs = {
            classes("is-flex", "is-align-items-center", "my-5", "has-text-grey")
        },
    ) {
        Hr(attrs = { classes("m-0", "has-background-light", "is-flex-grow-1") })
        Span(
            attrs = {
                classes(
                    "mx-3",
                    "is-flex",
                    "is-align-items-center",
                    "has-text-weight-semibold",
                )
            }
        ) {
            Span(attrs = { classes("icon", "is-small", "mr-2") }) {
                I(attrs = { classes(iconPrefix, icon) })
            }
            Text(label)
        }
        Hr(attrs = { classes("m-0", "has-background-light", "is-flex-grow-1") })
    }
}
