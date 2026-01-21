package com.alexn.socialpublish.pages

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.*

@Composable
fun HomePage() {
    Div(attrs = { classes("home") }) {
        Section(attrs = { classes("section") }) {
            Div(attrs = { classes("container", "block") }) {
                H1(attrs = { classes("title") }) {
                    Text("Social Publish")
                }
                P(attrs = { classes("subtitle") }) {
                    Text("Spam all your social media accounts at once!")
                }
            }

            Div(attrs = { classes("container", "box") }) {
                Ul(attrs = { classes("content", "is-medium") }) {
                    Li {
                        A(href = "/rss", attrs = {
                            attr("target", "_blank")
                        }) {
                            Text("RSS")
                        }
                    }
                    Li {
                        A(
                            href = "https://github.com/alexandru/social-publish",
                            attrs = {
                                attr("target", "_blank")
                                attr("rel", "noreferrer")
                            }
                        ) {
                            Text("GitHub")
                        }
                    }
                }
            }
        }
    }
}
