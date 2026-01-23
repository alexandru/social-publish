package socialpublish.frontend.components

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.utils.Storage

@Composable
fun NavBar(currentPath: String, onLogout: () -> Unit) {
    val isLoggedIn = Storage.hasJwtToken()
    var navbarActive by remember { mutableStateOf(false) }

    // Normalize currentPath to ignore trailing slashes and query strings so active checks are
    // robust
    val path =
        currentPath.substringBefore('?').let {
            when {
                it.isEmpty() -> "/"
                it != "/" && it.endsWith("/") -> it.removeSuffix("/")
                else -> it
            }
        }

    Nav(
        attrs = {
            classes("navbar", "is-primary")
            attr("role", "navigation")
            attr("aria-label", "main navigation")
        }
    ) {
        Div(attrs = { classes("navbar-brand") }) {
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
                        A(
                            href = "/",
                            attrs = {
                                classes("button", "is-primary")
                                if (currentPath == "/") classes("is-active")
                            },
                        ) {
                            Span(attrs = { classes("icon") }) {
                                I(attrs = { classes("fas", "fa-home") })
                            }
                            B { Text("Home") }
                        }
                    }
                }

                if (isLoggedIn) {
                    Div(attrs = { classes("navbar-item") }) {
                        Div(attrs = { classes("buttons") }) {
                            A(
                                href = "/form",
                                attrs = {
                                    classes("button", "is-primary")
                                    if (currentPath == "/form") classes("is-active")
                                },
                            ) {
                                Span(attrs = { classes("icon") }) {
                                    I(attrs = { classes("fas", "fa-paper-plane") })
                                }
                                B { Text("Publish") }
                            }
                        }
                    }
                }

                A(
                    href = "https://github.com/alexandru/social-publish",
                    attrs = {
                        classes("navbar-item")
                        attr("target", "_blank")
                        attr("rel", "noreferrer")
                    },
                ) {
                    Span(attrs = { classes("icon") }) { I(attrs = { classes("fab", "fa-github") }) }
                    B { Text("Help") }
                }
            }

            Div(attrs = { classes("navbar-end") }) {
                Div(attrs = { classes("navbar-item") }) {
                    Div(attrs = { classes("buttons") }) {
                        if (isLoggedIn) {
                            A(
                                href = "/account",
                                attrs = {
                                    classes("button", "is-primary")
                                    if (currentPath == "/account") classes("is-active")
                                },
                            ) {
                                Span(attrs = { classes("icon") }) {
                                    I(attrs = { classes("fas", "fa-user-circle") })
                                }
                                B { Text("Account") }
                            }

                            A(
                                attrs = {
                                    classes("button", "is-primary")
                                    onClick { onLogout() }
                                }
                            ) {
                                Span(attrs = { classes("icon") }) {
                                    I(attrs = { classes("fas", "fa-sign-out-alt") })
                                }
                                B { Text("Logout") }
                            }
                        } else {
                            A(
                                href = "/login",
                                attrs = {
                                    classes("button", "is-primary")
                                    if (currentPath == "/login") classes("is-active")
                                },
                            ) {
                                Span(attrs = { classes("icon") }) {
                                    I(attrs = { classes("fas", "fa-key") })
                                }
                                B { Text("Login") }
                            }
                        }
                    }
                }
            }
        }
    }
}
