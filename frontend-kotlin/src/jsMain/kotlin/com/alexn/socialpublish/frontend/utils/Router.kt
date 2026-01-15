package com.alexn.socialpublish.frontend.utils

import kotlinx.browser.window
import org.w3c.dom.events.Event
import react.useEffect
import react.useState

fun navigateTo(path: String) {
    if (window.location.pathname + window.location.search == path) {
        return
    }
    window.history.pushState(null, "", path)
    window.dispatchEvent(Event("popstate"))
}

fun useCurrentPath(): String {
    var path by useState(window.location.pathname + window.location.search)

    useEffect(dependencies = emptyArray()) {
        val handler: (Event) -> Unit = {
            path = window.location.pathname + window.location.search
        }
        window.addEventListener("popstate", handler)
        handler(Event("popstate"))
    }

    return path
}
