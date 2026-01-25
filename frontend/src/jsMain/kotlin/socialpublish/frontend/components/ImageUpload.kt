package socialpublish.frontend.components

import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get
import socialpublish.frontend.models.GenerateAltTextRequest
import socialpublish.frontend.models.GenerateAltTextResponse
import socialpublish.frontend.models.UpdateAltTextRequest
import socialpublish.frontend.models.UpdateAltTextResponse
import socialpublish.frontend.utils.ApiClient
import socialpublish.frontend.utils.ApiResponse

data class SelectedImage(
    val id: Int,
    val file: File? = null,
    val altText: String? = null,
    val uploadedUuid: String? = null,
)

@Composable
fun ImageUpload(
    id: Int,
    state: SelectedImage,
    onSelect: (SelectedImage) -> Unit,
    onRemove: (Int) -> Unit,
) {
    var imagePreviewUrl by remember { mutableStateOf<String?>(null) }
    var isGeneratingAltText by remember { mutableStateOf(false) }
    var altTextError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Generate image preview URL when file is selected
    LaunchedEffect(state.file) {
        if (state.file != null) {
            val reader = FileReader()
            reader.onload = { event ->
                try {
                    val result = event.target.asDynamic().result
                    imagePreviewUrl = result as? String
                } catch (e: Exception) {
                    console.error("Failed to read image file", e)
                    imagePreviewUrl = null
                }
            }
            reader.onerror = { console.error("Error reading file") }
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
                                    if (isGeneratingAltText) {
                                        attr("disabled", "")
                                    }
                                    onInput { event ->
                                        val target = event.target
                                        onSelect(state.copy(altText = target.value))
                                        altTextError = null
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
                                        if (isGeneratingAltText) {
                                            attr("disabled", "")
                                            classes("is-loading")
                                        }
                                        onClick { event ->
                                            event.preventDefault()
                                            if (!isGeneratingAltText) {
                                                isGeneratingAltText = true
                                                altTextError = null
                                                scope.launch {
                                                    try {
                                                        val response =
                                                            ApiClient.post<
                                                                GenerateAltTextResponse,
                                                                GenerateAltTextRequest,
                                                            >(
                                                                "/api/llm/generate-alt-text",
                                                                GenerateAltTextRequest(
                                                                    imageUuid = state.uploadedUuid
                                                                ),
                                                            )
                                                        when (response) {
                                                            is ApiResponse.Success -> {
                                                                // Update alt-text in database
                                                                val altText = response.data.altText
                                                                val updateResponse =
                                                                    ApiClient.post<
                                                                        UpdateAltTextResponse,
                                                                        UpdateAltTextRequest,
                                                                    >(
                                                                        "/api/files/${state.uploadedUuid}/alt-text",
                                                                        UpdateAltTextRequest(
                                                                            altText = altText
                                                                        ),
                                                                    )
                                                                when (updateResponse) {
                                                                    is ApiResponse.Success -> {
                                                                        // Successfully updated,
                                                                        // update local state
                                                                        onSelect(
                                                                            state.copy(
                                                                                altText = altText
                                                                            )
                                                                        )
                                                                    }
                                                                    is ApiResponse.Error -> {
                                                                        altTextError =
                                                                            "Failed to save alt-text: ${updateResponse.message}"
                                                                        console.error(
                                                                            "Failed to save alt-text:",
                                                                            updateResponse.message,
                                                                        )
                                                                    }
                                                                    is ApiResponse.Exception -> {
                                                                        altTextError =
                                                                            "Failed to save alt-text: ${updateResponse.message}"
                                                                        console.error(
                                                                            "Failed to save alt-text:",
                                                                            updateResponse.message,
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            is ApiResponse.Error -> {
                                                                altTextError = response.message
                                                                console.error(
                                                                    "Alt-text generation failed:",
                                                                    response.message,
                                                                )
                                                            }
                                                            is ApiResponse.Exception -> {
                                                                altTextError = response.message
                                                                console.error(
                                                                    "Alt-text generation exception:",
                                                                    response.message,
                                                                )
                                                            }
                                                        }
                                                    } finally {
                                                        isGeneratingAltText = false
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
                                                    if (isGeneratingAltText) "fa-spinner"
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
                        if (altTextError != null) {
                            Div(attrs = { classes("help", "is-danger") }) { Text(altTextError!!) }
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
