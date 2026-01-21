package com.alexn.socialpublish.components

import androidx.compose.runtime.*
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get

data class SelectedImage(
    val id: Int,
    val file: File? = null,
    val altText: String? = null
)

@Composable
fun ImageUpload(
    id: Int,
    state: SelectedImage,
    onSelect: (SelectedImage) -> Unit,
    onRemove: (Int) -> Unit
) {
    Div(attrs = { classes("block") }) {
        Div(attrs = { classes("field") }) {
            Label(attrs = { classes("label") }) {
                Text("Image ($id)")
            }
            Div(attrs = { classes("file") }) {
                Label(attrs = { classes("file-label") }) {
                    Input(type = InputType.File, attrs = {
                        classes("file-input")
                        id("file_$id")
                        attr("name", "file_$id")
                        attr("accept", "image/*")
                        onInput { event ->
                            val target = event.target as? org.w3c.dom.HTMLInputElement
                            val file = target?.files?.get(0)
                            if (file != null) {
                                onSelect(state.copy(file = file))
                            }
                        }
                    })
                    Span(attrs = { classes("file-cta") }) {
                        Span(attrs = { classes("file-icon") }) {
                            I(attrs = { classes("fas", "fa-upload") })
                        }
                        Span(attrs = { classes("file-label") }) {
                            Text("Choose an image file…")
                        }
                    }
                }
            }
            Div {
                P(attrs = { classes("help") }) {
                    Text("The image will be displayed in the post.")
                }
            }
            Div {
                Span {
                    Text(state.file?.name ?: "")
                }
            }
        }
        
        Div(attrs = { classes("field") }) {
            Label(attrs = { classes("label") }) {
                Text("Alt text for image ($id)")
            }
            Div(attrs = { classes("control") }) {
                TextArea(attrs = {
                    classes("textarea")
                    id("altText_$id")
                    attr("name", "altText_$id")
                    attr("rows", "2")
                    attr("cols", "50")
                    value(state.altText ?: "")
                    onInput { event ->
                        val target = event.target as? org.w3c.dom.HTMLTextAreaElement
                        onSelect(state.copy(altText = target?.value))
                    }
                })
            }
        }
        
        Div(attrs = { classes("field") }) {
            Button(attrs = {
                classes("button", "is-small")
                onClick { event ->
                    event.preventDefault()
                    onRemove(id)
                }
            }) {
                Text("Remove")
            }
        }
    }
}
