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
                Div(attrs = { classes("container", "block") }) {
                    H1(attrs = { classes("title") }) { Text("Social Publish") }
                    P(attrs = { classes("subtitle") }) {
                        Text("Spam all your social media accounts at once!")
                    }
                }

                Div(attrs = { classes("container") }) {
                    PostForm(onError = { errorMessage = it }, onInfo = { infoContent = it })
                }
            }
        }
    }
}

@Composable
private fun PostForm(onError: (String) -> Unit, onInfo: (@Composable () -> Unit) -> Unit) {
    var content by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf(setOf("rss")) }
    var cleanupHtml by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf(mapOf<Int, SelectedImage>()) }
    var isSubmitting by remember { mutableStateOf(false) }

    val hasAuth = Storage.getAuthStatus()
    val scope = rememberCoroutineScope()

    val postText = remember(content, link) { buildPostText(content, link) }
    val usedCharacters = remember(postText) { countCharactersWithLinks(postText) }
    val blueskyRemaining = remember(usedCharacters) { 300 - usedCharacters }
    val mastodonRemaining = remember(usedCharacters) { 500 - usedCharacters }
    val twitterRemaining = remember(usedCharacters) { 280 - usedCharacters }
    val linkedinRemaining = remember(usedCharacters) { 2000 - usedCharacters }

    val removeImage: (Int) -> Unit = { id -> images = images - id }

    val updateImage: (SelectedImage) -> Unit = { image -> images = images + (image.id to image) }

    val resetForm: () -> Unit = {
        content = ""
        link = ""
        targets = setOf("rss")
        cleanupHtml = false
        images = emptyMap()
    }

    val handleSubmit: () -> Unit = {
        scope.launch {
            if (content.isEmpty()) {
                onError("Content is required!")
                return@launch
            }

            if (targets.isEmpty()) {
                onError("At least one publication target is required!")
                return@launch
            }

            // Disable fieldset
            isSubmitting = true

            try {
                // Upload images first
                val imageUUIDs = mutableListOf<String>()
                for (image in images.values) {
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
                        content = content,
                        link = link.ifEmpty { null },
                        targets = targets.toList(),
                        images = imageUUIDs,
                        cleanupHtml = cleanupHtml,
                    )

                when (
                    val response =
                        ApiClient.post<Map<String, ModulePostResponse>, PublishRequest>(
                            "/api/multiple/post",
                            publishRequest,
                        )
                ) {
                    is ApiResponse.Success -> {
                        resetForm()
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
                isSubmitting = false
            }
        }
    }

    Form(
        attrs = {
            classes("box")
            addEventListener("submit") { event ->
                event.preventDefault()
                handleSubmit()
            }
        }
    ) {
        Fieldset(
            attrs = {
                id("post-form-fieldset")
                if (isSubmitting) {
                    attr("disabled", "")
                }
            }
        ) {
            TextAreaField(
                label = "Content",
                value = content,
                onValueChange = { content = it },
                rows = 4,
                required = true,
            )

            TextInputField(
                label = "Highlighted link (optional)",
                value = link,
                onValueChange = { link = it },
                placeholder = "https://example.com/...",
                pattern = "https?://.+",
            )

            images.values
                .sortedBy { it.id }
                .forEach { image ->
                    key(image.id) {
                        ImageUpload(
                            id = image.id,
                            state = image,
                            onSelect = updateImage,
                            onRemove = removeImage,
                        )
                    }
                }

            ServiceCheckboxField(
                serviceName = "Mastodon",
                checked = targets.contains("mastodon"),
                onCheckedChange = { checked ->
                    targets = if (checked) targets + "mastodon" else targets - "mastodon"
                },
                charactersRemaining = mastodonRemaining,
            )

            ServiceCheckboxField(
                serviceName = "Bluesky",
                checked = targets.contains("bluesky"),
                onCheckedChange = { checked ->
                    targets = if (checked) targets + "bluesky" else targets - "bluesky"
                },
                charactersRemaining = blueskyRemaining,
            )

            ServiceCheckboxField(
                serviceName = "Twitter",
                checked = targets.contains("twitter"),
                onCheckedChange = { checked ->
                    targets = if (checked) targets + "twitter" else targets - "twitter"
                },
                charactersRemaining = twitterRemaining,
                disabled = !hasAuth.twitter,
            )

            ServiceCheckboxField(
                serviceName = "LinkedIn",
                checked = targets.contains("linkedin"),
                onCheckedChange = { checked ->
                    targets = if (checked) targets + "linkedin" else targets - "linkedin"
                },
                charactersRemaining = linkedinRemaining,
                disabled = !hasAuth.linkedin,
            )

            ServiceCheckboxField(
                serviceName = "RSS feed",
                checked = targets.contains("rss"),
                onCheckedChange = { checked ->
                    targets = if (checked) targets + "rss" else targets - "rss"
                },
            ) {
                P(attrs = { classes("help") }) {
                    A(href = "/rss", attrs = { attr("target", "_blank") }) { Text("View feed") }
                }
            }

            CheckboxField(
                label = "cleanup HTML",
                checked = cleanupHtml,
                onCheckedChange = { cleanupHtml = it },
            )

            Div(attrs = { classes("field", "is-grouped") }) {
                Div(attrs = { classes("control") }) {
                    AddImageButton(
                        disabled = images.size >= 4,
                        onImageSelected = { file ->
                            val ids = images.keys.sorted()
                            val newId = if (ids.isEmpty()) 1 else ids.last() + 1
                            val newImage = SelectedImage(newId, file = file)
                            images = images + (newId to newImage)
                        },
                    )
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
