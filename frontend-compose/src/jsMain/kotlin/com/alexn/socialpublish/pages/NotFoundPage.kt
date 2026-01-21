package com.alexn.socialpublish.pages

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun NotFoundPage() {
    Div(attrs = { classes("notFound") }) {
        Section(attrs = { classes("section") }) {
            Div(attrs = { classes("container") }) {
                H1(attrs = { classes("title") }) {
                    Text("404: Not Found")
                }
                P(attrs = { classes("subtitle") }) {
                    Text("It's gone 😞")
                }
            }
        }
    }
}
