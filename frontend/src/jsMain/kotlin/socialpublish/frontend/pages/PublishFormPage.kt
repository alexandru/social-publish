package socialpublish.frontend.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.components.AddImageButton
import socialpublish.frontend.components.Authorize
import socialpublish.frontend.components.CharacterCounter
import socialpublish.frontend.components.ImageUpload
import socialpublish.frontend.components.MessageType
import socialpublish.frontend.components.ModalMessage
import socialpublish.frontend.components.PageContainer
import socialpublish.frontend.components.SelectedImage
import socialpublish.frontend.components.ServiceCheckboxField
import socialpublish.frontend.components.TextAreaField
import socialpublish.frontend.components.TextInputField
import socialpublish.frontend.models.FileUploadResponse
import socialpublish.frontend.models.ModulePostResponse
import socialpublish.frontend.models.PublishRequest
import socialpublish.frontend.utils.ApiClient
import socialpublish.frontend.utils.ApiResponse
import socialpublish.frontend.utils.Storage
import socialpublish.frontend.utils.navigateTo

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

        PageContainer("publish-form") {
            PostForm(onError = { errorMessage = it }, onInfo = { infoContent = it })
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

            formState = formState.setSubmitting(true)

            try {
                val imageUUIDs = mutableListOf<String>()
                for (image in formState.images.values) {
                    if (image.file != null) {
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
                                    navigateTo("/login?error=${response.code}&redirect=/form")
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
                            navigateTo("/login?error=${response.code}&redirect=/form")
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
                if (formState.isFormDisabled) {
                    attr("disabled", "")
                }
            }
        ) {
            // Distribution channels box
            Div(attrs = { classes("box", "mb-4") }) {
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

            Div(attrs = { classes("box", "mb-4") }) {
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

                Div(attrs = { classes("columns", "is-multiline") }) {
                    formState.images.values
                        .sortedBy { it.id }
                        .forEach { image ->
                            Div(
                                attrs = { classes("column", "is-half-tablet", "is-half-desktop") }
                            ) {
                                key(image.id) {
                                    ImageUpload(
                                        id = image.id,
                                        state = image,
                                        onSelect = { formState = formState.updateImage(it) },
                                        onRemove = { formState = formState.removeImage(it) },
                                        onError = onError,
                                    )
                                }
                            }
                        }
                }

                Div(attrs = { classes("field") }) {
                    Div(attrs = { classes("control") }) {
                        AddImageButton(
                            disabled = formState.images.size >= 4 || formState.isFormDisabled,
                            onImageSelected = { file ->
                                scope.launch {
                                    formState = formState.setProcessing(true)

                                    try {
                                        val ids = formState.images.keys.sorted()
                                        val newId = if (ids.isEmpty()) 1 else ids.last() + 1

                                        val response =
                                            ApiClient.uploadFile<FileUploadResponse>(
                                                "/api/files/upload",
                                                file,
                                                null,
                                            )

                                        when (response) {
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
                                                    navigateTo(
                                                        "/login?error=${response.code}&redirect=/form"
                                                    )
                                                    return@launch
                                                }
                                                onError(
                                                    "Error uploading image: ${response.message}"
                                                )
                                                console.error(
                                                    "Error uploading image:",
                                                    response.message,
                                                )
                                            }
                                            is ApiResponse.Exception -> {
                                                onError(
                                                    "Connection error: Could not reach the server. Please check your connection and try again."
                                                )
                                                console.error(
                                                    "Error uploading image:",
                                                    response.message,
                                                )
                                            }
                                        }
                                    } finally {
                                        formState = formState.setProcessing(false)
                                    }
                                }
                            },
                        )
                    }
                }
            }

            Div(attrs = { classes("box", "mb-4") }) {
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
