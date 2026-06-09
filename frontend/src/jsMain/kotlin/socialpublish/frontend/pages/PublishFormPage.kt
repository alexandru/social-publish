package socialpublish.frontend.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.*
import org.w3c.files.File
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
import socialpublish.frontend.components.TextAreaField
import socialpublish.frontend.components.TextInputField
import socialpublish.frontend.utils.ApiClient
import socialpublish.frontend.utils.ApiResponse
import socialpublish.frontend.utils.Storage
import socialpublish.frontend.utils.buildLoginRedirectPath
import socialpublish.frontend.utils.isUnauthorized
import socialpublish.frontend.utils.navigateTo
import socialpublish.frontend.utils.rethrowIfFatal

@Serializable internal data class FileUploadResponse(val uuid: String)

@Serializable internal data class FileAltTextPatch(val altText: String? = null)

@Serializable
internal data class PublishRequest(
    val targets: List<String>,
    val language: String? = null,
    val messages: List<PublishRequestMessage>,
)

@Serializable
internal data class PublishRequestMessage(
    val content: String,
    val link: String? = null,
    val images: List<String>? = null,
)

@Serializable
internal data class ModulePostResponse(
    val module: String,
    val uri: String? = null,
    val id: String? = null,
    val cid: String? = null,
    val postId: String? = null,
    val messages: List<ModulePostMessage>? = null,
)

@Serializable
internal data class ModulePostMessage(
    val id: String,
    val uri: String? = null,
    val replyToId: String? = null,
    val commentOnId: String? = null,
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
        var infoContent by remember {
            mutableStateOf<(@Composable () -> Unit)?>(null)
        }

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
            PostForm(
                onError = { errorMessage = it },
                onInfo = { infoContent = it },
            )
        }
    }
}

private fun redirectToLoginIfUnauthorized(
    response: ApiResponse<*>,
    currentPath: String,
): Boolean {
    if (!isUnauthorized(response)) {
        return false
    }
    Storage.clearSessionToken()
    Storage.setConfiguredServices(null)
    navigateTo(buildLoginRedirectPath(currentPath))
    return true
}

@Composable
private fun PostForm(
    onError: (String) -> Unit,
    onInfo: (@Composable () -> Unit) -> Unit,
) {
    var formState by remember { mutableStateOf(PublishFormState()) }
    val latestFormState by rememberUpdatedState(formState)

    val configuredServices = Storage.getConfiguredServices()
    val feedHref = "#"
    val scope = rememberCoroutineScope()

    val handleSubmit: (PublishFormState) -> Unit = { submittedState ->
        scope.launch {
            submittedState.validateForSubmit().firstOrNull()?.let { error ->
                onError(error.message)
                return@launch
            }

            formState = submittedState.setSubmitting(true)

            try {
                val requestMessages = mutableListOf<PublishRequestMessage>()
                for ((index, message) in submittedState.messages.withIndex()) {
                    val imageUUIDs = mutableListOf<String>()
                    for (image in message.images.values) {
                        val response = image.prepareForPublish() ?: continue
                        when (response) {
                            is ApiResponse.Success ->
                                imageUUIDs.add(response.data.uuid)
                            is ApiResponse.Error -> {
                                if (
                                    redirectToLoginIfUnauthorized(
                                        response,
                                        "/form",
                                    )
                                ) {
                                    return@launch
                                }
                                onError(
                                    "Error preparing image for post ${index + 1}: ${response.message}"
                                )
                                return@launch
                            }
                            is ApiResponse.Exception -> {
                                onError(
                                    "Error preparing image for post ${index + 1}: ${response.message}"
                                )
                                return@launch
                            }
                        }
                    }

                    requestMessages.add(
                        PublishRequestMessage(
                            content = message.content,
                            link = message.link.ifEmpty { null },
                            images = imageUUIDs.takeIf { it.isNotEmpty() },
                        )
                    )
                }

                val publishRequest =
                    PublishRequest(
                        targets = submittedState.targets.toList(),
                        language = submittedState.language,
                        messages = requestMessages,
                    )

                when (
                    val response =
                        ApiClient.post<
                            Map<String, ModulePostResponse>,
                            PublishRequest,
                        >(
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
                                    A(
                                        href = feedHref,
                                        attrs = { attr("target", "_blank") },
                                    ) {
                                        Text("feed?")
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
                        console.error(
                            "Exception while submitting form: ${response.message}"
                        )
                        onError("Unexpected exception while submitting form!")
                    }
                }
            } catch (e: Throwable) {
                rethrowIfFatal(e)
                console.error("Exception while submitting form:", e)
                onError("Unexpected exception while submitting form!")
            } finally {
                formState = formState.setSubmitting(false)
            }
        }
    }

    LaunchedEffect(formState.messages.size) {
        if (formState.messages.size <= 1) return@LaunchedEffect
        val id = formState.messages.last().id
        val card = document.getElementById("message-card-$id")
        card?.scrollIntoView()
    }

    Form(
        attrs = {
            onSubmit { event ->
                event.preventDefault()
                if (window.confirm("Publish this thread now?")) {
                    handleSubmit(latestFormState)
                }
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
            Div(attrs = { classes("box", "mb-4") }) {
                SectionHeader(
                    icon = "fa-share-nodes",
                    title = "Publish targets",
                )

                Div(attrs = { classes("is-flex", "is-flex-wrap-wrap") }) {
                    PublishTargetCheckbox(
                        serviceName = "Mastodon",
                        target = "mastodon",
                        formState = formState,
                        configured = configuredServices.mastodon,
                        onToggle = { formState = formState.toggleTarget(it) },
                    )

                    PublishTargetCheckbox(
                        serviceName = "Bluesky",
                        target = "bluesky",
                        formState = formState,
                        configured = configuredServices.bluesky,
                        onToggle = { formState = formState.toggleTarget(it) },
                    )

                    PublishTargetCheckbox(
                        serviceName = "Twitter",
                        target = "twitter",
                        formState = formState,
                        configured = configuredServices.twitter,
                        onToggle = { formState = formState.toggleTarget(it) },
                    )

                    PublishTargetCheckbox(
                        serviceName = "LinkedIn",
                        target = "linkedin",
                        formState = formState,
                        configured = configuredServices.linkedin,
                        onToggle = { formState = formState.toggleTarget(it) },
                    )

                    PublishTargetCheckbox(
                        serviceName = "Feed",
                        target = "feed",
                        formState = formState,
                        configured = true,
                        onToggle = { formState = formState.toggleTarget(it) },
                    )
                }

                formState.targetGuidanceWarnings.forEach { warning ->
                    P(
                        attrs = {
                            classes("help", "is-warning", "mt-2", "mb-0")
                        }
                    ) {
                        Text(warning)
                    }
                }
                formState.unavailableTargetWarnings.forEach { warning ->
                    P(attrs = { classes("help", "mt-2", "mb-0") }) {
                        Text(warning)
                    }
                }
            }

            Div(attrs = { classes("box", "mb-4") }) {
                SectionHeader(icon = "fa-globe", title = "Language")
                SelectInputField(
                    label = null,
                    value = formState.language,
                    onValueChange = {
                        it?.let { formState = formState.updateLanguage(it) }
                    },
                    options = LANGUAGE_OPTIONS,
                    icon = "fa-globe",
                )
            }

            Div(attrs = { classes("mb-4") }) {
                formState.messages.forEachIndexed { index, message ->
                    key(message.id) {
                        MessageComposerCard(
                            message = message,
                            messageNumber = index + 1,
                            canRemove = formState.messages.size > 1,
                            remaining =
                                formState.charactersRemainingFor(message),
                            maximum = formState.maxCharactersFor(message),
                            isFormDisabled = formState.isFormDisabled,
                            canAddImage = formState.canAddImageTo(message),
                            language = formState.language,
                            onContentChange = {
                                formState =
                                    formState.updateMessageContent(
                                        message.id,
                                        it,
                                    )
                            },
                            onLinkChange = {
                                formState =
                                    formState.updateMessageLink(message.id, it)
                            },
                            onRemoveMessage = {
                                formState = formState.removeMessage(message.id)
                            },
                            onImageSelected = { image ->
                                formState =
                                    formState.updateImage(message.id, image)
                            },
                            onImageRemoved = { imageId ->
                                formState =
                                    formState.removeImage(message.id, imageId)
                            },
                            onError = onError,
                            onAddImage = { file ->
                                scope.launch {
                                    formState = formState.setProcessing(true)

                                    try {
                                        val currentMessage =
                                            formState.messages.first {
                                                it.id == message.id
                                            }
                                        val ids =
                                            currentMessage.images.keys.sorted()
                                        val newId =
                                            if (ids.isEmpty()) 1
                                            else ids.last() + 1

                                        val response =
                                            ApiClient.uploadFile<
                                                FileUploadResponse
                                            >(
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
                                                        uploadedUuid =
                                                            response.data.uuid,
                                                    )
                                                formState =
                                                    formState.addImage(
                                                        message.id,
                                                        newImage,
                                                    )
                                            }
                                            is ApiResponse.Error -> {
                                                if (
                                                    redirectToLoginIfUnauthorized(
                                                        response,
                                                        "/form",
                                                    )
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
                                        formState =
                                            formState.setProcessing(false)
                                    }
                                }
                            },
                        )
                    }
                }

                if (formState.targets.contains("linkedin")) {
                    CharacterCounter(
                        remaining = formState.linkedinCharactersRemaining,
                        maximum = PublishFormState.LINKEDIN_LIMIT,
                    )
                    P(attrs = { classes("help") }) {
                        Text(
                            "LinkedIn images: ${formState.linkedinImageCount} of ${PublishFormState.LINKEDIN_MAX_IMAGES} across the thread"
                        )
                    }
                }
            }

            Div(
                attrs = {
                    classes(
                        "field",
                        "is-grouped",
                        "is-justify-content-flex-end",
                        "mt-4",
                    )
                }
            ) {
                Div(attrs = { classes("control") }) {
                    Button(
                        attrs = {
                            classes("button", "is-link", "is-light")
                            attr("type", "button")
                            if (formState.isFormDisabled) {
                                attr("disabled", "")
                            }
                            onClick { formState = formState.addMessage() }
                        }
                    ) {
                        Span(attrs = { classes("icon") }) {
                            I(attrs = { classes("fas", "fa-plus") })
                        }
                        Span { Text("Add post") }
                    }
                }
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

private suspend fun SelectedImage.prepareForPublish():
    ApiResponse<FileUploadResponse>? {
    val altText = altText?.takeIf { it.isNotBlank() }
    val uploadedUuid = uploadedUuid
    if (uploadedUuid != null) {
        return ApiClient.patch<FileUploadResponse, FileAltTextPatch>(
            "/api/files/$uploadedUuid",
            FileAltTextPatch(altText),
        )
    }

    val file = file ?: return null
    return ApiClient.uploadFile<FileUploadResponse>(
        "/api/files/upload",
        file,
        altText,
    )
}

@Composable
private fun MessageComposerCard(
    message: PublishMessageState,
    messageNumber: Int,
    canRemove: Boolean,
    remaining: Int,
    maximum: Int,
    isFormDisabled: Boolean,
    canAddImage: Boolean,
    language: String?,
    onContentChange: (String) -> Unit,
    onLinkChange: (String) -> Unit,
    onRemoveMessage: () -> Unit,
    onImageSelected: (SelectedImage) -> Unit,
    onImageRemoved: (Int) -> Unit,
    onAddImage: (File) -> Unit,
    onError: (String) -> Unit,
) {
    Div(
        attrs = {
            id("message-card-${message.id}")
            classes("box", "mb-4")
        }
    ) {
        Div(
            attrs = {
                classes(
                    "is-flex",
                    "is-justify-content-space-between",
                    "is-align-items-center",
                    "mb-2",
                )
            }
        ) {
            P(attrs = { classes("title", "is-6", "mb-0") }) {
                Text("Post $messageNumber")
            }
            if (canRemove) {
                Button(
                    attrs = {
                        classes("button", "is-small", "is-danger", "is-light")
                        attr("type", "button")
                        if (isFormDisabled) {
                            attr("disabled", "")
                        }
                        onClick { onRemoveMessage() }
                    }
                ) {
                    Text("Remove")
                }
            }
        }

        TextAreaField(
            label = null,
            value = message.content,
            onValueChange = onContentChange,
            rows = 4,
            required = true,
            placeholder = "Write here...",
        )

        TextInputField(
            label = null,
            value = message.link,
            onValueChange = onLinkChange,
            placeholder = "Highlighted URL (optional): https://example.com/...",
            pattern = "https?://.+",
        )

        CharacterCounter(remaining = remaining, maximum = maximum)

        Div(
            attrs = {
                classes("columns", "is-multiline", "is-variable", "is-2")
            }
        ) {
            message.images.values
                .sortedBy { it.id }
                .forEach { image ->
                    Div(
                        attrs = {
                            classes(
                                "column",
                                "is-half-tablet",
                                "is-half-desktop",
                            )
                        }
                    ) {
                        key(image.id) {
                            ImageUpload(
                                id = image.id,
                                state = image,
                                onSelect = onImageSelected,
                                onRemove = onImageRemoved,
                                onError = onError,
                                language = language,
                            )
                        }
                    }
                }
        }

        Div(attrs = { classes("field") }) {
            Div(attrs = { classes("control") }) {
                AddImageButton(
                    disabled = !canAddImage || isFormDisabled,
                    onImageSelected = onAddImage,
                )
            }
        }
    }
}

@Composable
private fun PublishTargetCheckbox(
    serviceName: String,
    target: String,
    formState: PublishFormState,
    configured: Boolean,
    onToggle: (String) -> Unit,
) {
    val checked = formState.targets.contains(target)
    val disabled =
        !configured || (!checked && !formState.isTargetSupported(target))
    val colorClasses = targetColorClasses(target, checked)

    Label(
        attrs = {
            classes(
                "tag",
                "is-medium",
                "is-rounded",
                "mr-2",
                "mb-2",
                *colorClasses,
            )
        }
    ) {
        Input(
            type = InputType.Checkbox,
            attrs = {
                checked(checked)
                if (disabled) attr("disabled", "")
                onInput { onToggle(target) }
            },
        )
        Span(attrs = { classes("icon", "is-small", "ml-1") }) {
            I(attrs = { classes(*targetIcon(target)) })
        }
        Span(attrs = { classes("ml-1") }) { Text(serviceName) }
    }
}

@Composable
private fun SectionHeader(icon: String, title: String) {
    Div(attrs = { classes("is-flex", "is-align-items-center", "mb-2") }) {
        Span(attrs = { classes("icon", "mr-2", "has-text-link") }) {
            I(attrs = { classes("fas", icon) })
        }
        P(attrs = { classes("title", "is-6", "mb-0") }) { Text(title) }
    }
}

private fun targetColorClasses(
    target: String,
    checked: Boolean,
): Array<String> {
    if (!checked) return arrayOf("is-light")
    return when (target) {
        "mastodon" -> arrayOf("is-link")
        "bluesky" -> arrayOf("is-info")
        "twitter" -> arrayOf("is-dark")
        "linkedin" -> arrayOf("is-link")
        else -> arrayOf("is-success")
    }
}

private fun targetIcon(target: String): Array<String> =
    when (target) {
        "mastodon" -> arrayOf("fab", "fa-mastodon")
        "bluesky" -> arrayOf("fab", "fa-bluesky")
        "twitter" -> arrayOf("fab", "fa-x-twitter")
        "linkedin" -> arrayOf("fab", "fa-linkedin")
        else -> arrayOf("fas", "fa-rss")
    }
