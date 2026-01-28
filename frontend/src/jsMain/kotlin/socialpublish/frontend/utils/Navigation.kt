package socialpublish.frontend.utils

import kotlinx.browser.window
import org.w3c.dom.events.Event

/** Navigate to a new path using the History API without a full page reload. */
fun navigateTo(path: String) {
    if (window.location.pathname != path) {
        window.history.pushState(null, "", path)
        window.dispatchEvent(Event("popstate"))
    }
}
