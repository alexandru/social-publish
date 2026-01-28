package socialpublish.frontend.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.browser.document
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get
import socialpublish.frontend.models.GenerateAltTextRequest
import socialpublish.frontend.models.GenerateAltTextResponse
import socialpublish.frontend.utils.ApiClient
import socialpublish.frontend.utils.ApiResponse

data class SelectedImage(
    val id: Int,
    val file: File? = null,
    val altText: String? = null,
    val uploadedUuid: String? = null,
    val uploadError: String? = null,
    val imagePreviewUrl: String? = null,
    val isGeneratingAltText: Boolean = false,
    val altTextError: String? = null,
)

@Composable
fun ImageUpload(
    id: Int,
    state: SelectedImage,
    onSelect: (SelectedImage) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Generate image preview URL when file is selected
    LaunchedEffect(state.file) {
        if (state.file != null && state.imagePreviewUrl == null) {
            val reader = FileReader()
            reader.onload = { event ->
                try {
                    val result = event.target.asDynamic().result
                    val previewUrl = result as? String
                    onSelect(state.copy(imagePreviewUrl = previewUrl))
                } catch (e: Exception) {
                    console.error("Failed to read image file", e)
                    onSelect(state.copy(imagePreviewUrl = null))
                }
            }
            reader.onerror = { console.error("Error reading file") }
            reader.readAsDataURL(state.file)
        } else if (state.file == null && state.imagePreviewUrl != null) {
            onSelect(state.copy(imagePreviewUrl = null))
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
                if (state.imagePreviewUrl != null) {
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
                                src = state.imagePreviewUrl,
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
                        // Show upload error if initial upload failed
                        if (state.uploadError != null) {
                            Div(attrs = { classes("help", "is-danger") }) {
                                Text("Upload failed: ${state.uploadError}")
                            }
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
                                    if (state.isGeneratingAltText) {
                                        attr("disabled", "")
                                    }
                                    onInput { event ->
                                        val target = event.target
                                        onSelect(
                                            state.copy(altText = target.value, altTextError = null)
                                        )
                                    }
                                }
                            )
                        }
                        // Generate Alt-Text button
                        if (state.uploadedUuid != null) {
                            Div(attrs = { classes("control", "mt-2") }) {
                                Button(
                                    attrs = {
                                        classes("button", "is-small", "is-info", "is-outlined")
                                        attr("type", "button")
                                        if (state.isGeneratingAltText) {
                                            attr("disabled", "")
                                            classes("is-loading")
                                        }
                                        onClick { event ->
                                            event.preventDefault()
                                            if (!state.isGeneratingAltText) {
                                                onSelect(
                                                    state.copy(
                                                        isGeneratingAltText = true,
                                                        altTextError = null,
                                                    )
                                                )
                                                scope.launch {
                                                    try {
                                                        val response =
                                                            ApiClient.post<
                                                                GenerateAltTextResponse,
                                                                GenerateAltTextRequest,
                                                            >(
                                                                "/api/llm/generate-alt-text",
                                                                GenerateAltTextRequest(
                                                                    imageUuid = state.uploadedUuid,
                                                                    userContext = state.altText,
                                                                ),
                                                            )

                                                        when (response) {
                                                            is ApiResponse.Success -> {
                                                                val altText = response.data.altText
                                                                onSelect(
                                                                    state.copy(
                                                                        altText = altText,
                                                                        isGeneratingAltText = false,
                                                                    )
                                                                )
                                                            }
                                                            is ApiResponse.Error -> {
                                                                if (response.code == 401) {
                                                                    kotlinx.browser.window.location
                                                                        .href =
                                                                        "/login?error=${response.code}&redirect=/form"
                                                                    return@launch
                                                                }
                                                                onSelect(
                                                                    state.copy(
                                                                        altTextError =
                                                                            response.message,
                                                                        isGeneratingAltText = false,
                                                                    )
                                                                )
                                                                console.error(
                                                                    "Alt-text generation failed:",
                                                                    response.message,
                                                                )
                                                            }
                                                            is ApiResponse.Exception -> {
                                                                onSelect(
                                                                    state.copy(
                                                                        altTextError =
                                                                            "Connection error: Could not reach the server. Please check your connection and try again.",
                                                                        isGeneratingAltText = false,
                                                                    )
                                                                )
                                                                console.error(
                                                                    "Alt-text generation exception:",
                                                                    response.message,
                                                                )
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        onSelect(
                                                            state.copy(
                                                                altTextError =
                                                                    "An unexpected error occurred",
                                                                isGeneratingAltText = false,
                                                            )
                                                        )
                                                        console.error("Unexpected error:", e)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Span(attrs = { classes("icon", "is-small") }) {
                                        I(
                                            attrs = {
                                                classes(
                                                    "fas",
                                                    if (state.isGeneratingAltText) "fa-spinner"
                                                    else "fa-wand-magic-sparkles",
                                                )
                                            }
                                        )
                                    }
                                    Span { Text("Generate Alt-Text") }
                                }
                            }
                        }
                        // Show error message if generation failed
                        if (state.altTextError != null) {
                            Div(attrs = { classes("help", "is-danger") }) {
                                Text(state.altTextError)
                            }
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
                            if (state.isGeneratingAltText) {
                                attr("disabled", "")
                            }
                            onClick { event ->
                                event.preventDefault()
                                if (!state.isGeneratingAltText) {
                                    onRemove(id)
                                }
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
    // Hidden file input - use Long for better uniqueness
    val inputId = remember { "hidden-file-input-${kotlin.random.Random.nextLong()}" }

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
                    // Reset input to allow selecting the same file again if removed
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
                if (input != null) {
                    input.click()
                } else {
                    console.error("Could not find hidden file input with id: $inputId")
                }
            }
        }
    ) {
        Span(attrs = { classes("icon") }) { I(attrs = { classes("fas", "fa-plus") }) }
        Span { Text("Add image") }
    }
}
