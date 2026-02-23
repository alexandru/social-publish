package socialpublish.frontend.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.web.dom.*
import socialpublish.frontend.components.AddImageButton
import socialpublish.frontend.components.Authorize
import socialpublish.frontend.components.CharacterCounter
import socialpublish.frontend.components.ImageUpload
import socialpublish.frontend.components.MessageType
import socialpublish.frontend.components.ModalMessage
import socialpublish.frontend.components.PageContainer
import socialpublish.frontend.components.SelectInputField
import socialpublish.frontend.components.SelectOption
import socialpublish.frontend.components.SelectedImage
import socialpublish.frontend.components.ServiceCheckboxField
import socialpublish.frontend.components.TextAreaField
import socialpublish.frontend.components.TextInputField
import socialpublish.frontend.utils.ApiClient
import socialpublish.frontend.utils.ApiResponse
import socialpublish.frontend.utils.Storage
import socialpublish.frontend.utils.buildLoginRedirectPath
import socialpublish.frontend.utils.isUnauthorized
import socialpublish.frontend.utils.navigateTo

@Serializable internal data class FileUploadResponse(val uuid: String)

@Serializable
internal data class PublishRequest(
    val content: String,
    val link: String? = null,
    val targets: List<String>,
    val images: List<String> = emptyList(),
    val language: String? = null,
)

@Serializable
internal data class ModulePostResponse(
    val module: String,
    val uri: String? = null,
    val id: String? = null,
    val cid: String? = null,
)

internal val LANGUAGE_OPTIONS =
    listOf(
        SelectOption("English", "en"),
        SelectOption("Arabic", "ar"),
        SelectOption("Basque", "eu"),
        SelectOption("Bengali", "bn"),
        SelectOption("Bulgarian", "bg"),
        SelectOption("Catalan", "ca"),
        SelectOption("Chinese", "zh"),
        SelectOption("Croatian", "hr"),
        SelectOption("Czech", "cs"),
        SelectOption("Danish", "da"),
        SelectOption("Dutch", "nl"),
        SelectOption("Estonian", "et"),
        SelectOption("Finnish", "fi"),
        SelectOption("French", "fr"),
        SelectOption("Galician", "gl"),
        SelectOption("German", "de"),
        SelectOption("Greek", "el"),
        SelectOption("Hindi", "hi"),
        SelectOption("Hungarian", "hu"),
        SelectOption("Icelandic", "is"),
        SelectOption("Indonesian", "id"),
        SelectOption("Irish", "ga"),
        SelectOption("Italian", "it"),
        SelectOption("Japanese", "ja"),
        SelectOption("Korean", "ko"),
        SelectOption("Latvian", "lv"),
        SelectOption("Lithuanian", "lt"),
        SelectOption("Maltese", "mt"),
        SelectOption("Norwegian", "no"),
        SelectOption("Polish", "pl"),
        SelectOption("Portuguese", "pt"),
        SelectOption("Punjabi", "pa"),
        SelectOption("Romanian", "ro"),
        SelectOption("Russian", "ru"),
        SelectOption("Serbian", "sr"),
        SelectOption("Slovak", "sk"),
        SelectOption("Slovenian", "sl"),
        SelectOption("Spanish", "es"),
        SelectOption("Swedish", "sv"),
        SelectOption("Thai", "th"),
        SelectOption("Turkish", "tr"),
        SelectOption("Ukrainian", "uk"),
        SelectOption("Vietnamese", "vi"),
    )

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

private fun redirectToLoginIfUnauthorized(response: ApiResponse<*>, currentPath: String): Boolean {
    if (!isUnauthorized(response)) {
        return false
    }
    Storage.clearJwtToken()
    Storage.setConfiguredServices(null)
    navigateTo(buildLoginRedirectPath(currentPath))
    return true
}

@Composable
private fun PostForm(onError: (String) -> Unit, onInfo: (@Composable () -> Unit) -> Unit) {
    var formState by remember { mutableStateOf(PublishFormState()) }

    val configuredServices = Storage.getConfiguredServices()
    val rssFeedHref = Storage.getJwtUserUuid()?.let { "/rss/$it" } ?: "#"
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
                                if (redirectToLoginIfUnauthorized(response, "/form")) {
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
                        language = formState.language,
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
                                    A(href = rssFeedHref, attrs = { attr("target", "_blank") }) {
                                        Text("RSS feed?")
                                    }
                                }
                            }
                        }
                    }
                    is ApiResponse.Error -> {
                        if (redirectToLoginIfUnauthorized(response, "/form")) {
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
                        disabled = !configuredServices.mastodon,
                    )

                    ServiceCheckboxField(
                        serviceName = "Bluesky",
                        checked = formState.targets.contains("bluesky"),
                        onCheckedChange = { _ -> formState = formState.toggleTarget("bluesky") },
                        disabled = !configuredServices.bluesky,
                    )

                    ServiceCheckboxField(
                        serviceName = "Twitter",
                        checked = formState.targets.contains("twitter"),
                        onCheckedChange = { _ -> formState = formState.toggleTarget("twitter") },
                        disabled = !configuredServices.twitter,
                    )

                    ServiceCheckboxField(
                        serviceName = "LinkedIn",
                        checked = formState.targets.contains("linkedin"),
                        onCheckedChange = { _ -> formState = formState.toggleTarget("linkedin") },
                        disabled = !configuredServices.linkedin,
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
                    label = null,
                    value = formState.link,
                    onValueChange = { formState = formState.updateLink(it) },
                    placeholder = "Highlighted URL (optional): https://example.com/...",
                    pattern = "https?://.+",
                )

                CharacterCounter(
                    remaining = formState.charactersRemaining,
                    maximum = formState.maxCharacters,
                )

                SelectInputField(
                    label = null,
                    value = formState.language,
                    onValueChange = { formState = formState.updateLanguage(it) },
                    options = listOf(SelectOption("Language", null)).plus(LANGUAGE_OPTIONS),
                    icon = "fa-globe",
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
                                        language = formState.language,
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
                                                if (
                                                    redirectToLoginIfUnauthorized(response, "/form")
                                                ) {
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
