package socialpublish.frontend.components

import androidx.compose.runtime.*
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.utils.Storage
import socialpublish.frontend.utils.navigateTo

@Composable
fun NavBar(currentPath: String, onLogout: () -> Unit) {
    val isLoggedIn = Storage.hasJwtToken()
    var navbarActive by remember { mutableStateOf(false) }

    // Normalize currentPath to ignore trailing slashes and query strings so active checks are
    // robust

    Nav(
        attrs = {
            classes("navbar", "is-primary", "has-shadow")
            attr("role", "navigation")
            attr("aria-label", "main navigation")
        }
    ) {
        Div(attrs = { classes("navbar-brand") }) {
            A(
                href = "/",
                attrs = {
                    classes("navbar-item")
                    style {
                        property("display", "flex")
                        property("align-items", "center")
                        property("gap", "0.5rem")
                    }
                    onClick { event ->
                        event.preventDefault()
                        navigateTo("/")
                    }
                },
            ) {
                Img(
                    src = "/assets/logos/cloud-192x192.png",
                    attrs = {
                        attr("alt", "Social Publish Logo")
                        style {
                            property("height", "32px")
                            property("width", "32px")
                        }
                    },
                )
                Span(
                    attrs = {
                        style {
                            property("font-weight", "bold")
                            property("font-size", "1.25rem")
                            property("color", "#ffffff")
                            property(
                                "text-shadow",
                                "0 0 2px rgba(0, 0, 0, 0.8), 0 0 4px rgba(0, 0, 0, 0.2)",
                            )
                        }
                    }
                ) {
                    Text("Social Publish")
                }
            }

            A(
                attrs = {
                    classes("navbar-burger")
                    if (navbarActive) classes("is-active")
                    attr("role", "button")
                    attr("aria-label", "menu")
                    attr("aria-expanded", "false")
                    onClick { navbarActive = !navbarActive }
                }
            ) {
                Span(attrs = { attr("aria-hidden", "true") })
                Span(attrs = { attr("aria-hidden", "true") })
                Span(attrs = { attr("aria-hidden", "true") })
            }
        }

        Div(
            attrs = {
                classes("navbar-menu")
                if (navbarActive) classes("is-active")
            }
        ) {
            Div(attrs = { classes("navbar-start") }) {
                Div(attrs = { classes("navbar-item") }) {
                    Div(attrs = { classes("buttons") }) {
                        // Home
                        NavButton(
                            href = "/",
                            label = "Home",
                            iconClasses = arrayOf("fas", "fa-home"),
                            isActive = (currentPath == "/"),
                            onClick = { navbarActive = false },
                        )
                    }
                }

                if (isLoggedIn) {
                    Div(attrs = { classes("navbar-item") }) {
                        Div(attrs = { classes("buttons") }) {
                            // Publish
                            NavButton(
                                href = "/form",
                                label = "Publish",
                                iconClasses = arrayOf("fas", "fa-paper-plane"),
                                isActive = (currentPath == "/form"),
                                onClick = { navbarActive = false },
                            )
                        }
                    }
                }

                // New API link (opens in new tab)
                NavItemLink(
                    href = "/docs",
                    label = "API",
                    iconClasses = arrayOf("fas", "fa-book"),
                    openInNewTab = true,
                )

                // Help
                NavItemLink(
                    href = "https://github.com/alexandru/social-publish",
                    label = "Help",
                    iconClasses = arrayOf("fab", "fa-github"),
                    openInNewTab = true,
                )
            }

            Div(attrs = { classes("navbar-end") }) {
                Div(attrs = { classes("navbar-item") }) {
                    Div(attrs = { classes("buttons") }) {
                        if (isLoggedIn) {
                            // Account
                            NavButton(
                                href = "/account",
                                label = "Account",
                                iconClasses = arrayOf("fas", "fa-user-circle"),
                                isActive = (currentPath == "/account"),
                                onClick = { navbarActive = false },
                            )

                            // Logout (action)
                            NavButton(
                                label = "Logout",
                                iconClasses = arrayOf("fas", "fa-sign-out-alt"),
                                onClick = {
                                    navbarActive = false
                                    onLogout()
                                },
                            )
                        } else {
                            // Login
                            NavButton(
                                href = "/login",
                                label = "Login",
                                iconClasses = arrayOf("fas", "fa-key"),
                                isActive = (currentPath == "/login"),
                                onClick = { navbarActive = false },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavButton(
    href: String? = null,
    label: String,
    iconClasses: Array<String> = emptyArray(),
    isActive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    if (href != null) {
        A(
            href = href,
            attrs = {
                classes("button", "is-primary")
                if (isActive) classes("is-active")
                onClick { event ->
                    event.preventDefault()
                    if (onClick != null) {
                        onClick()
                    }
                    navigateTo(href)
                }
            },
        ) {
            Span(attrs = { classes("icon") }) { I(attrs = { classes(*iconClasses) }) }
            B { Text(label) }
        }
    } else {
        A(
            attrs = {
                classes("button", "is-primary")
                if (onClick != null) onClick { onClick() }
            }
        ) {
            Span(attrs = { classes("icon") }) { I(attrs = { classes(*iconClasses) }) }
            B { Text(label) }
        }
    }
}

@Composable
private fun NavItemLink(
    href: String,
    label: String,
    iconClasses: Array<String> = emptyArray(),
    openInNewTab: Boolean = false,
) {
    A(
        href = href,
        attrs = {
            classes("navbar-item")
            if (openInNewTab) {
                attr("target", "_blank")
                attr("rel", "noreferrer")
            }
        },
    ) {
        Span(attrs = { classes("icon") }) { I(attrs = { classes(*iconClasses) }) }
        B { Text(label) }
    }
}
