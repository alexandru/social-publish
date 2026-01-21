package com.alexn.socialpublish.pages

import androidx.compose.runtime.*
import com.alexn.socialpublish.components.*
import com.alexn.socialpublish.models.FileUploadResponse
import com.alexn.socialpublish.models.PublishRequest
import com.alexn.socialpublish.models.ModulePostResponse
import com.alexn.socialpublish.utils.ApiClient
import com.alexn.socialpublish.utils.ApiResponse
import com.alexn.socialpublish.utils.Storage
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLFieldSetElement

@Composable
fun PublishFormPage() {
    Authorize {
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var infoContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
        
        if (errorMessage != null) {
            ModalMessage(
                type = MessageType.ERROR,
                isEnabled = true,
                onDisable = { errorMessage = null }
            ) {
                Text(errorMessage ?: "")
            }
        }
        
        if (infoContent != null) {
            ModalMessage(
                type = MessageType.INFO,
                isEnabled = true,
                onDisable = { infoContent = null }
            ) {
                infoContent?.invoke()
            }
        }
        
        Div(attrs = { classes("publish-form") }) {
            Section(attrs = { classes("section") }) {
                Div(attrs = { classes("container", "block") }) {
                    H1(attrs = { classes("title") }) {
                        Text("Social Publish")
                    }
                    P(attrs = { classes("subtitle") }) {
                        Text("Spam all your social media accounts at once!")
                    }
                }
                
                Div(attrs = { classes("container") }) {
                    PostForm(
                        onError = { errorMessage = it },
                        onInfo = { infoContent = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun PostForm(
    onError: (String) -> Unit,
    onInfo: (@Composable () -> Unit) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf(emptySet<String>()) }
    var cleanupHtml by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf(mapOf<Int, SelectedImage>()) }
    var isSubmitting by remember { mutableStateOf(false) }
    
    val hasAuth = Storage.getAuthStatus()
    val scope = rememberCoroutineScope()
    
    val charsLeft = remember(content, link) {
        val text = listOf(content, link).filter { it.isNotEmpty() }.joinToString("\n\n")
        280 - text.length
    }
    
    val addImage: () -> Unit = {
        val ids = images.keys.sorted()
        val newId = if (ids.isEmpty()) 1 else ids.last() + 1
        images = images + (newId to SelectedImage(newId))
    }
    
    val removeImage: (Int) -> Unit = { id ->
        images = images - id
    }
    
    val updateImage: (SelectedImage) -> Unit = { image ->
        images = images + (image.id to image)
    }
    
    val resetForm: () -> Unit = {
        content = ""
        link = ""
        targets = emptySet()
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
            val fieldset = document.getElementById("post-form-fieldset") as? HTMLFieldSetElement
            fieldset?.setAttribute("disabled", "true")
            
            try {
                // Upload images first
                val imageUUIDs = mutableListOf<String>()
                for (image in images.values) {
                    if (image.file != null) {
                        when (val response = ApiClient.uploadFile<FileUploadResponse>(
                            "/api/files/upload",
                            image.file,
                            image.altText
                        )) {
                            is ApiResponse.Success -> {
                                imageUUIDs.add(response.data.uuid)
                            }
                            is ApiResponse.Error -> {
                                if (response.code in listOf(401, 403)) {
                                    window.location.href = "/login?error=${response.code}&redirect=/form"
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
                val publishRequest = PublishRequest(
                    content = content,
                    link = link.ifEmpty { null },
                    targets = targets.toList(),
                    images = imageUUIDs,
                    cleanupHtml = cleanupHtml
                )
                
                when (val response = ApiClient.post<Map<String, ModulePostResponse>, PublishRequest>(
                    "/api/multiple/post",
                    publishRequest
                )) {
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
                        if (response.code in listOf(401, 403)) {
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
                fieldset?.removeAttribute("disabled")
            }
        }
    }
    
    Form(attrs = {
        classes("box")
        addEventListener("submit") { event ->
            event.preventDefault()
            handleSubmit()
        }
    }) {
        Fieldset(attrs = { id("post-form-fieldset") }) {
            Div(attrs = { classes("field") }) {
                Label(attrs = { classes("label") }) {
                    Text("Content")
                }
                Div(attrs = { classes("control") }) {
                    TextArea(attrs = {
                        classes("textarea")
                        id("content")
                        attr("name", "content")
                        attr("rows", "4")
                        attr("cols", "50")
                        attr("required", "")
                        value(content)
                        onInput { event ->
                            val target = event.target as? org.w3c.dom.HTMLTextAreaElement
                            content = target?.value ?: ""
                        }
                    })
                }
                P(attrs = { classes("help") }) {
                    Text("Characters left: $charsLeft")
                }
            }
            
            Div(attrs = { classes("field") }) {
                Label(attrs = { classes("label") }) {
                    Text("Highlighted link (optional)")
                }
                Div(attrs = { classes("control") }) {
                    Input(type = InputType.Text, attrs = {
                        classes("input")
                        id("link")
                        attr("name", "link")
                        attr("placeholder", "https://example.com/...")
                        attr("pattern", "https?://.+")
                        value(link)
                        onInput { event ->
                            link = event.value
                        }
                    })
                }
            }
            
            images.values.sortedBy { it.id }.forEach { image ->
                key(image.id) {
                    ImageUpload(
                        id = image.id,
                        state = image,
                        onSelect = updateImage,
                        onRemove = removeImage
                    )
                }
            }
            
            Div(attrs = { classes("field") }) {
                Label(attrs = { classes("checkbox") }) {
                    Input(type = InputType.Checkbox, attrs = {
                        id("mastodon")
                        attr("name", "mastodon")
                        checked(targets.contains("mastodon"))
                        onInput { event ->
                            val target = event.target as? org.w3c.dom.HTMLInputElement
                            targets = if (target?.checked == true) {
                                targets + "mastodon"
                            } else {
                                targets - "mastodon"
                            }
                        }
                    })
                    Text(" Mastodon")
                }
            }
            
            Div(attrs = { classes("field") }) {
                Label(attrs = { classes("checkbox") }) {
                    Input(type = InputType.Checkbox, attrs = {
                        id("bluesky")
                        attr("name", "bluesky")
                        checked(targets.contains("bluesky"))
                        onInput { event ->
                            val target = event.target as? org.w3c.dom.HTMLInputElement
                            targets = if (target?.checked == true) {
                                targets + "bluesky"
                            } else {
                                targets - "bluesky"
                            }
                        }
                    })
                    Text(" Bluesky")
                }
            }
            
            Div(attrs = { classes("field") }) {
                Label(attrs = { classes("checkbox") }) {
                    Input(type = InputType.Checkbox, attrs = {
                        id("twitter")
                        attr("name", "twitter")
                        if (!hasAuth.twitter) {
                            attr("disabled", "")
                        }
                        checked(targets.contains("twitter"))
                        onInput { event ->
                            val target = event.target as? org.w3c.dom.HTMLInputElement
                            targets = if (target?.checked == true) {
                                targets + "twitter"
                            } else {
                                targets - "twitter"
                            }
                        }
                    })
                    Text(" Twitter")
                }
            }
            
            Div(attrs = { classes("field") }) {
                Label(attrs = { classes("checkbox") }) {
                    Input(type = InputType.Checkbox, attrs = {
                        id("linkedin")
                        attr("name", "linkedin")
                        checked(targets.contains("linkedin"))
                        onInput { event ->
                            val target = event.target as? org.w3c.dom.HTMLInputElement
                            targets = if (target?.checked == true) {
                                targets + "linkedin"
                            } else {
                                targets - "linkedin"
                            }
                        }
                    })
                    Text(" LinkedIn")
                }
                P(attrs = { classes("help") }) {
                    A(href = "/rss/target/linkedin", attrs = { attr("target", "_blank") }) {
                        Text("Via RSS feed")
                    }
                    Text(" (needs ")
                    A(href = "https://ifttt.com", attrs = {
                        attr("target", "_blank")
                        attr("rel", "noreferrer")
                    }) {
                        Text("ifttt.com")
                    }
                    Text(" setup)")
                }
            }
            
            Div(attrs = { classes("field") }) {
                Label(attrs = { classes("checkbox") }) {
                    Input(type = InputType.Checkbox, attrs = {
                        id("cleanupHtml")
                        attr("name", "cleanupHtml")
                        checked(cleanupHtml)
                        onInput { event ->
                            val target = event.target as? org.w3c.dom.HTMLInputElement
                            cleanupHtml = target?.checked ?: false
                        }
                    })
                    Text(" cleanup HTML")
                }
            }
            
            Input(type = InputType.Button, attrs = {
                classes("button")
                id("post-form-reset-button")
                value("Reset")
                onClick { resetForm() }
            })
            Text(" ")
            if (images.size < 4) {
                Button(attrs = {
                    classes("button")
                    attr("type", "button")
                    onClick { addImage() }
                }) {
                    Text("Add image")
                }
                Text(" ")
            }
            Input(type = InputType.Submit, attrs = {
                classes("button", "is-primary")
                value("Submit")
            })
        }
    }
}
