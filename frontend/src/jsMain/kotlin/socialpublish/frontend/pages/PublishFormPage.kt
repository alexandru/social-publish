package socialpublish.frontend.pages

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.components.*
import socialpublish.frontend.models.FileUploadResponse
import socialpublish.frontend.models.ModulePostResponse
import socialpublish.frontend.models.PublishRequest
import socialpublish.frontend.utils.ApiClient
import socialpublish.frontend.utils.ApiResponse
import socialpublish.frontend.utils.Storage

@Composable
fun PublishFormPage() {
    Authorize {
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var infoContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

        if (errorMessage != null) {
            ModalMessage(
                type = MessageType.ERROR,
                isEnabled = true,
                onDisable = { errorMessage = null },
            ) {
                Text(errorMessage ?: "")
            }
        }

        if (infoContent != null) {
            ModalMessage(
                type = MessageType.INFO,
                isEnabled = true,
                onDisable = { infoContent = null },
            ) {
                infoContent?.invoke()
            }
        }

        Div(attrs = { classes("publish-form") }) {
            Section(attrs = { classes("section") }) {
                Div(attrs = { classes("container") }) {
                    PostForm(onError = { errorMessage = it }, onInfo = { infoContent = it })
                }
            }
        }
    }
}

@Composable
private fun PostForm(onError: (String) -> Unit, onInfo: (@Composable () -> Unit) -> Unit) {
    var formState by remember { mutableStateOf(PublishFormState()) }

    val hasAuth = Storage.getAuthStatus()
    val scope = rememberCoroutineScope()

    val handleSubmit: () -> Unit = {
        scope.launch {
            if (formState.content.isEmpty()) {
                onError("Content is required!")
                return@launch
            }

            if (formState.targets.isEmpty()) {
                onError("At least one publication target is required!")
                return@launch
            }

            // Disable fieldset
            formState = formState.setSubmitting(true)

            try {
                // Upload images with current alt-text
                val imageUUIDs = mutableListOf<String>()
                for (image in formState.images.values) {
                    if (image.file != null) {
                        // Upload/re-upload image with current alt-text
                        when (
                            val response =
                                ApiClient.uploadFile<FileUploadResponse>(
                                    "/api/files/upload",
                                    image.file,
                                    image.altText,
                                )
                        ) {
                            is ApiResponse.Success -> {
                                imageUUIDs.add(response.data.uuid)
                            }
                            is ApiResponse.Error -> {
                                if (response.code == 401) {
                                    window.location.href =
                                        "/login?error=${response.code}&redirect=/form"
                                    return@launch
                                }
                                onError("Error uploading image: ${response.message}")
                                return@launch
                            }
                            is ApiResponse.Exception -> {
                                onError("Error uploading image: ${response.message}")
                                return@launch
                            }
                        }
                    }
                }

                // Submit the form
                val publishRequest =
                    PublishRequest(
                        content = formState.content,
                        link = formState.link.ifEmpty { null },
                        targets = formState.targets.toList(),
                        images = imageUUIDs,
                    )

                when (
                    val response =
                        ApiClient.post<Map<String, ModulePostResponse>, PublishRequest>(
                            "/api/multiple/post",
                            publishRequest,
                        )
                ) {
                    is ApiResponse.Success -> {
                        formState = formState.reset()
                        onInfo {
                            Div {
                                P { Text("New post created successfully!") }
                                P {
                                    Text("View the ")
                                    A(href = "/rss", attrs = { attr("target", "_blank") }) {
                                        Text("RSS feed?")
                                    }
                                }
                            }
                        }
                    }
                    is ApiResponse.Error -> {
                        if (response.code == 401) {
                            window.location.href = "/login?error=${response.code}&redirect=/form"
                            return@launch
                        }
                        onError("Error submitting form: ${response.message}")
                    }
                    is ApiResponse.Exception -> {
                        console.error("Exception while submitting form: ${response.message}")
                        onError("Unexpected exception while submitting form!")
                    }
                }
            } catch (e: Exception) {
                console.error("Exception while submitting form:", e)
                onError("Unexpected exception while submitting form!")
            } finally {
                formState = formState.setSubmitting(false)
            }
        }
    }

    Form(
        attrs = {
            addEventListener("submit") { event ->
                event.preventDefault()
                handleSubmit()
            }
        }
    ) {
        Fieldset(
            attrs = {
                id("post-form-fieldset")
                if (formState.isSubmitting) {
                    attr("disabled", "")
                }
            }
        ) {
            // Distribution channels box
            Div(attrs = { classes("box") }) {
                Div(attrs = { classes("checkboxes") }) {
                    ServiceCheckboxField(
                        serviceName = "Mastodon",
                        checked = formState.targets.contains("mastodon"),
                        onCheckedChange = { _ -> formState = formState.toggleTarget("mastodon") },
                    )

                    ServiceCheckboxField(
                        serviceName = "Bluesky",
                        checked = formState.targets.contains("bluesky"),
                        onCheckedChange = { _ -> formState = formState.toggleTarget("bluesky") },
                    )

                    ServiceCheckboxField(
                        serviceName = "Twitter",
                        checked = formState.targets.contains("twitter"),
                        onCheckedChange = { _ -> formState = formState.toggleTarget("twitter") },
                        disabled = !hasAuth.twitter,
                    )

                    ServiceCheckboxField(
                        serviceName = "LinkedIn",
                        checked = formState.targets.contains("linkedin"),
                        onCheckedChange = { _ -> formState = formState.toggleTarget("linkedin") },
                        disabled = !hasAuth.linkedin,
                    )

                    ServiceCheckboxField(
                        serviceName = "RSS feed",
                        checked = formState.targets.contains("rss"),
                        onCheckedChange = { _ -> formState = formState.toggleTarget("rss") },
                    )
                }
            }

            // Message box
            Div(attrs = { classes("box") }) {
                TextAreaField(
                    label = "Message",
                    value = formState.content,
                    onValueChange = { formState = formState.updateContent(it) },
                    rows = 4,
                    required = true,
                    placeholder = "Write here...",
                )

                TextInputField(
                    label = "Highlighted link (optional)",
                    value = formState.link,
                    onValueChange = { formState = formState.updateLink(it) },
                    placeholder = "https://example.com/...",
                    pattern = "https?://.+",
                )

                CharacterCounter(
                    remaining = formState.charactersRemaining,
                    maximum = formState.maxCharacters,
                )

                formState.images.values
                    .sortedBy { it.id }
                    .forEach { image ->
                        key(image.id) {
                            ImageUpload(
                                id = image.id,
                                state = image,
                                onSelect = { formState = formState.updateImage(it) },
                                onRemove = { formState = formState.removeImage(it) },
                            )
                        }
                    }

                Div(attrs = { classes("field") }) {
                    Div(attrs = { classes("control") }) {
                        AddImageButton(
                            disabled = formState.images.size >= 4,
                            onImageSelected = { file ->
                                scope.launch {
                                    // Compute new ID once before upload to avoid race conditions
                                    val ids = formState.images.keys.sorted()
                                    val newId = if (ids.isEmpty()) 1 else ids.last() + 1

                                    // Upload image immediately to get UUID for alt-text generation
                                    when (
                                        val response =
                                            ApiClient.uploadFile<FileUploadResponse>(
                                                "/api/files/upload",
                                                file,
                                                null, // No alt-text yet
                                            )
                                    ) {
                                        is ApiResponse.Success -> {
                                            val newImage =
                                                SelectedImage(
                                                    newId,
                                                    file = file,
                                                    uploadedUuid = response.data.uuid,
                                                )
                                            formState = formState.addImage(newImage)
                                        }
                                        is ApiResponse.Error -> {
                                            if (response.code == 401) {
                                                window.location.href =
                                                    "/login?error=${response.code}&redirect=/form"
                                                return@launch
                                            }
                                            console.error(
                                                "Error uploading image:",
                                                response.message,
                                            )
                                            // Add image locally with error message
                                            val newImage =
                                                SelectedImage(
                                                    newId,
                                                    file = file,
                                                    uploadError = response.message,
                                                )
                                            formState = formState.addImage(newImage)
                                        }
                                        is ApiResponse.Exception -> {
                                            console.error(
                                                "Error uploading image:",
                                                response.message,
                                            )
                                            // Add image locally with connection error message
                                            val newImage =
                                                SelectedImage(
                                                    newId,
                                                    file = file,
                                                    uploadError =
                                                        "Connection error: Could not reach the server. Please check your connection and try again.",
                                                )
                                            formState = formState.addImage(newImage)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // Submit button box
            Div(attrs = { classes("box") }) {
                Div(attrs = { classes("field") }) {
                    Div(attrs = { classes("control") }) {
                        Button(
                            attrs = {
                                classes("button", "is-primary")
                                attr("type", "submit")
                            }
                        ) {
                            Span(attrs = { classes("icon") }) {
                                I(attrs = { classes("fas", "fa-paper-plane") })
                            }
                            Span { Text("Submit") }
                        }
                    }
                }
            }
        }
    }
}
