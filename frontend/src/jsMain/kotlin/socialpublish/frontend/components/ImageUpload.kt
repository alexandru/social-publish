package socialpublish.frontend.components

import androidx.compose.runtime.*
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get

data class SelectedImage(val id: Int, val file: File? = null, val altText: String? = null)

@Composable
fun ImageUpload(
    id: Int,
    state: SelectedImage,
    onSelect: (SelectedImage) -> Unit,
    onRemove: (Int) -> Unit,
) {
    var imagePreviewUrl by remember { mutableStateOf<String?>(null) }

    // Generate image preview URL when file is selected
    LaunchedEffect(state.file) {
        if (state.file != null) {
            val reader = FileReader()
            reader.onload = { event -> imagePreviewUrl = event.target.asDynamic().result as String }
            reader.readAsDataURL(state.file)
        } else {
            imagePreviewUrl = null
        }
    }

    // Card-based layout for selected images
    Div(
        attrs = {
            classes("card", "mb-4")
            style {
                property("border", "1px solid #dbdbdb")
                property("border-radius", "6px")
                property("overflow", "hidden")
            }
        }
    ) {
        Div(attrs = { classes("card-content", "p-4") }) {
            Div(
                attrs = {
                    classes("columns", "is-mobile")
                    style { property("align-items", "center") }
                }
            ) {
                // Image preview column
                if (imagePreviewUrl != null) {
                    Div(attrs = { classes("column", "is-narrow") }) {
                        Div(
                            attrs = {
                                style {
                                    width(80.px)
                                    height(80.px)
                                    property("overflow", "hidden")
                                    property("border-radius", "4px")
                                    property("border", "1px solid #dbdbdb")
                                    property("display", "flex")
                                    property("align-items", "center")
                                    property("justify-content", "center")
                                    property("background-color", "#f5f5f5")
                                }
                            }
                        ) {
                            Img(
                                src = imagePreviewUrl ?: "",
                                attrs = {
                                    style {
                                        property("max-width", "100%")
                                        property("max-height", "100%")
                                        property("object-fit", "contain")
                                    }
                                    attr("alt", "Preview")
                                },
                            )
                        }
                    }
                }

                // Image info and controls column
                Div(attrs = { classes("column") }) {
                    Div(attrs = { classes("field") }) {
                        Label(attrs = { classes("label", "is-small") }) {
                            Text("File: ${state.file?.name ?: "No file selected"}")
                        }
                    }

                    Div(attrs = { classes("field") }) {
                        Label(attrs = { classes("label", "is-small") }) { Text("Alt text") }
                        Div(attrs = { classes("control") }) {
                            TextArea(
                                attrs = {
                                    classes("textarea", "is-small")
                                    id("altText_$id")
                                    attr("name", "altText_$id")
                                    attr("rows", "2")
                                    attr("placeholder", "Describe this image for accessibility...")
                                    value(state.altText ?: "")
                                    onInput { event ->
                                        val target = event.target
                                        onSelect(state.copy(altText = target.value))
                                    }
                                }
                            )
                        }
                    }
                }

                // Remove button column
                Div(attrs = { classes("column", "is-narrow") }) {
                    Button(
                        attrs = {
                            classes("button", "is-danger", "is-small")
                            attr("type", "button")
                            attr("title", "Remove image")
                            onClick { event ->
                                event.preventDefault()
                                onRemove(id)
                            }
                        }
                    ) {
                        Span(attrs = { classes("icon", "is-small") }) {
                            I(attrs = { classes("fas", "fa-trash") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddImageButton(onImageSelected: (File) -> Unit, disabled: Boolean = false) {
    // Hidden file input
    val inputId = remember { "hidden-file-input-${kotlin.random.Random.nextInt()}" }

    Input(
        type = InputType.File,
        attrs = {
            id(inputId)
            attr("accept", "image/*")
            style { property("display", "none") }
            onInput { event ->
                val target = event.target
                val file = target.files?.get(0)
                if (file != null) {
                    onImageSelected(file)
                    // Reset input so the same file can be selected again
                    target.value = ""
                }
            }
        },
    )

    // Button that triggers file input
    Button(
        attrs = {
            classes("button", "is-info")
            attr("type", "button")
            if (disabled) {
                attr("disabled", "")
            }
            onClick { event ->
                event.preventDefault()
                val input = document.getElementById(inputId) as? HTMLInputElement
                input?.click()
            }
        }
    ) {
        Span(attrs = { classes("icon") }) { I(attrs = { classes("fas", "fa-plus") }) }
        Span { Text("Add image") }
    }
}
