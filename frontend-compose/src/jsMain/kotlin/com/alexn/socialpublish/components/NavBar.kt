package com.alexn.socialpublish.components

import androidx.compose.runtime.*
import com.alexn.socialpublish.utils.Storage
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun NavBar(currentPath: String, onLogout: () -> Unit) {
    val isLoggedIn = Storage.hasJwtToken()
    var navbarActive by remember { mutableStateOf(false) }

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
                A(
                    href = "/",
                    attrs = {
                        classes("navbar-item")
                        if (currentPath == "/") classes("is-active")
                    },
                ) {
                    Span(attrs = { classes("icon") }) { Text("🏠") }
                    B { Text("Home") }
                }

                if (isLoggedIn) {
                    A(
                        href = "/form",
                        attrs = {
                            classes("navbar-item")
                            if (currentPath == "/form") classes("is-active")
                        },
                    ) {
                        Span(attrs = { classes("icon") }) { Text("▶️") }
                        B { Text("Publish") }
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
                    Span(attrs = { classes("icon") }) { Text("📖") }
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
                                B { Text("Account") }
                            }

                            A(
                                attrs = {
                                    classes("button", "is-primary")
                                    onClick { onLogout() }
                                }
                            ) {
                                B { Text("Logout") }
                                Span(attrs = { classes("icon") }) { Text("🚪") }
                            }
                        } else {
                            A(
                                href = "/login",
                                attrs = {
                                    classes("button", "is-primary")
                                    if (currentPath == "/login") classes("is-active")
                                },
                            ) {
                                Span(attrs = { classes("icon") }) { Text("🔑") }
                                B { Text("Login") }
                            }
                        }
                    }
                }
            }
        }
    }
}
