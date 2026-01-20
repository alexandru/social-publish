@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.alexn.socialpublish.frontend.pages

import com.alexn.socialpublish.frontend.components.Authorize
import com.alexn.socialpublish.frontend.components.ImageUpload
import com.alexn.socialpublish.frontend.components.ModalMessage
import com.alexn.socialpublish.frontend.models.MessageType
import com.alexn.socialpublish.frontend.models.PublishFormData
import com.alexn.socialpublish.frontend.models.SelectedImage
import com.alexn.socialpublish.frontend.models.Target
import com.alexn.socialpublish.frontend.utils.getAuthStatus
import com.alexn.socialpublish.frontend.utils.navigateOptionsWithSearch
import com.alexn.socialpublish.frontend.utils.parseJsonObject
import com.alexn.socialpublish.frontend.utils.toClassName
import com.alexn.socialpublish.frontend.utils.toElementId
import com.alexn.socialpublish.frontend.utils.toInputType
import com.alexn.socialpublish.frontend.utils.toRequestMethod
import com.alexn.socialpublish.frontend.utils.toWindowTarget
import js.promise.await
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import react.FC
import react.Props
import react.dom.events.FormEvent
import react.dom.events.MouseEvent
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.form
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.section
import react.dom.html.ReactHTML.textarea
import react.useMemo
import react.useRef
import react.useState
import tanstack.react.router.useNavigate
import web.console.console
import web.dom.document
import web.form.FormData
import web.html.HTMLFieldSetElement
import web.html.HTMLFormElement
import web.html.HTMLInputElement
import web.html.HTMLTextAreaElement
import web.http.BodyInit
import web.http.Headers
import web.http.RequestInit
import web.http.fetch

external interface CharsLeftProps : Props {
  var data: PublishFormData
}

val CharsLeft =
  FC<CharsLeftProps> { props ->
    val text =
      listOfNotNull(props.data.content, props.data.link)
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
    p {
      className = "help".toClassName()
      +"Characters left: ${280 - text.length}"
    }
  }

external interface PostFormProps : Props {
  var onError: (String) -> Unit
  var onInfo: (String, String?, String?) -> Unit
}

val PostForm =
  FC<PostFormProps> { props ->
    val formRef = useRef<HTMLFormElement>(null)
    var data by useState(PublishFormData())
    var images by useState<Map<Int, SelectedImage>>(emptyMap())
    val hasAuth = getAuthStatus()
    val scope = useMemo { MainScope() }
    val navigate = useNavigate()

    val addImageComponent = {
      val ids = images.keys.sorted()
      val newId = if (ids.isNotEmpty()) ids.last() + 1 else 1
      images = images + (newId to SelectedImage(id = newId))
    }

    val removeImageComponent: (Int) -> Unit = { id -> images = images - id }

    val onSelectedFile: (SelectedImage) -> Unit = { value -> images = images + (value.id to value) }

    suspend fun withDisabledForm(block: suspend () -> Unit) {
      val fieldset =
        document.getElementById("post-form-fieldset".toElementId()) as? HTMLFieldSetElement
      fieldset?.setAttribute("disabled", "true")
      try {
        block()
      } finally {
        fieldset?.removeAttribute("disabled")
      }
    }

    val handleSubmit: (FormEvent<*>) -> Unit = { event ->
      event.preventDefault()
      scope.launch {
        withDisabledForm {
          if (data.content.isNullOrBlank()) {
            props.onError("Content is required!")
            return@withDisabledForm
          }

          if (data.targets.isEmpty()) {
            props.onError("At least one publication target is required!")
            return@withDisabledForm
          }

          val imageIds = mutableListOf<String>()
          val imagesToUpload = images.values
          for (image in imagesToUpload) {
            val file = image.file
            if (file != null) {
              val formData = FormData()
              formData.append("file", file)
              image.altText?.let { formData.append("altText", it) }

              val response =
                fetch(
                  "/api/files/upload",
                  RequestInit(method = "POST".toRequestMethod(), body = BodyInit(formData)),
                )
              if (response.status == 401.toShort() || response.status == 403.toShort()) {
                navigate(
                  navigateOptionsWithSearch(
                    to = "/login",
                    searchParams = mapOf("error" to "${response.status}", "redirect" to "/form"),
                  )
                )
                return@withDisabledForm
              }
              if (response.status != 200.toShort()) {
                val text = response.textAsync().await()
                props.onError("Error uploading image: HTTP ${response.status} / $text")
                return@withDisabledForm
              }
              val bodyText = response.textAsync().await()
              val jsonResponse = parseJsonObject(bodyText)
              val uuid = jsonResponse?.get("uuid")?.jsonPrimitive?.contentOrNull
              if (uuid != null) {
                imageIds.add(uuid)
              }
            }
          }

          val payload = buildJsonObject {
            put("content", JsonPrimitive(data.content.orEmpty()))
            data.link?.let { put("link", JsonPrimitive(it)) }
            put("targets", JsonArray(data.targets.map { JsonPrimitive(it.apiValue) }))
            data.rss?.let { put("rss", JsonPrimitive(it)) }
            if (data.cleanupHtml) {
              put("cleanupHtml", JsonPrimitive("1"))
            }
            put("images", JsonArray(imageIds.map { JsonPrimitive(it) }))
          }
          val payloadText = Json.encodeToString(JsonObject.serializer(), payload)

          try {
            val response =
              fetch(
                "/api/multiple/post",
                RequestInit(
                  method = "POST".toRequestMethod(),
                  headers = Headers().apply { set("Content-Type", "application/json") },
                  body = BodyInit(payloadText),
                ),
              )
            if (response.status == 401.toShort() || response.status == 403.toShort()) {
              navigate(
                navigateOptionsWithSearch(
                  to = "/login",
                  searchParams = mapOf("error" to "${response.status}", "redirect" to "/form"),
                )
              )
              return@withDisabledForm
            }
            if (response.status != 200.toShort()) {
              val text = response.textAsync().await()
              props.onError("Error submitting form: HTTP ${response.status} / $text")
              return@withDisabledForm
            }
            formRef.current?.reset()
            data = PublishFormData()
            images = emptyMap()

            props.onInfo("New post created successfully!", "RSS feed?", "/rss")
          } catch (exception: Throwable) {
            // In Kotlin/JS, JavaScript errors may not be Kotlin exceptions
            // Catch as Throwable (covers both Kotlin and JS errors)
            props.onError("Unexpected exception while submitting form!")
            console.error(exception)
          }
        }
      }
    }

    val handleInputChange = { fieldName: String ->
      { event: FormEvent<*> ->
        val value =
          when (val target = event.target) {
            is HTMLInputElement -> target.value
            is HTMLTextAreaElement -> target.value
            else -> ""
          }
        data =
          when (fieldName) {
            "content" -> data.copy(content = value)
            "link" -> data.copy(link = value)
            else -> data
          }
      }
    }

    val handleTargetCheck = { target: Target ->
      { event: FormEvent<*> ->
        val input = event.target as? HTMLInputElement
        val checked = input?.checked == true
        val newTargets = data.targets.toMutableList()
        if (checked) {
          if (!newTargets.contains(target)) {
            newTargets.add(target)
          }
        } else {
          newTargets.remove(target)
        }
        data = data.copy(targets = newTargets)
      }
    }

    val handleCheckbox = { fieldName: String ->
      { event: FormEvent<*> ->
        val input = event.target as? HTMLInputElement
        val checked = input?.checked == true
        if (fieldName == "cleanupHtml") {
          data = data.copy(cleanupHtml = checked)
        }
      }
    }

    form {
      ref = formRef
      onSubmit = handleSubmit
      className = "box".toClassName()
      react.dom.html.ReactHTML.fieldset {
        id = "post-form-fieldset".toElementId()
        div {
          className = "field".toClassName()
          label {
            className = "label".toClassName()
            +"Content"
          }
          div {
            className = "control".toClassName()
            textarea {
              id = "content".toElementId()
              name = "content"
              rows = 4
              cols = 50
              className = "textarea".toClassName()
              onInput = handleInputChange("content")
              required = true
            }
          }
          CharsLeft { this.data = data }
        }
        div {
          className = "field".toClassName()
          label {
            className = "label".toClassName()
            +"Highlighted link (optional)"
          }
          div {
            className = "control".toClassName()
            input {
              type = "text".toInputType()
              className = "input".toClassName()
              placeholder = "https://example.com/..."
              id = "link".toElementId()
              name = "link"
              onInput = handleInputChange("link")
              pattern = "https?://.+"
            }
          }
        }
        images.values.forEach { image ->
          ImageUpload {
            id = image.id
            state = image
            onSelect = onSelectedFile
            onRemove = removeImageComponent
          }
        }
        div {
          className = "field".toClassName()
          label {
            className = "checkbox".toClassName()
            input {
              type = "checkbox".toInputType()
              id = "mastodon".toElementId()
              name = "mastodon"
              onInput = handleTargetCheck(Target.MASTODON)
            }
            +" Mastodon"
          }
        }
        div {
          className = "field".toClassName()
          label {
            className = "checkbox".toClassName()
            input {
              type = "checkbox".toInputType()
              id = "bluesky".toElementId()
              name = "bluesky"
              onInput = handleTargetCheck(Target.BLUESKY)
            }
            +" Bluesky"
          }
        }
        div {
          className = "field".toClassName()
          label {
            className = "checkbox".toClassName()
            if (hasAuth.twitter) {
              input {
                type = "checkbox".toInputType()
                id = "twitter".toElementId()
                name = "twitter"
                onInput = handleTargetCheck(Target.TWITTER)
              }
            } else {
              input {
                type = "checkbox".toInputType()
                id = "twitter".toElementId()
                name = "twitter"
                disabled = true
              }
            }
            +" Twitter"
          }
        }
        div {
          className = "field".toClassName()
          label {
            className = "checkbox".toClassName()
            input {
              type = "checkbox".toInputType()
              id = "linkedin".toElementId()
              name = "linkedin"
              onInput = handleTargetCheck(Target.LINKEDIN)
            }
            +" LinkedIn"
          }
          p {
            className = "help".toClassName()
            a {
              href = "/rss/target/linkedin"
              target = "_blank".toWindowTarget()
              +"Via RSS feed"
            }
            +" (needs "
            a {
              href = "https://ifttt.com"
              target = "_blank".toWindowTarget()
              rel = "noreferrer"
              +"ifttt.com"
            }
            +" setup)"
          }
        }
        div {
          className = "field".toClassName()
          label {
            className = "checkbox".toClassName()
            input {
              type = "checkbox".toInputType()
              id = "cleanupHtml".toElementId()
              name = "cleanupHtml"
              onInput = handleCheckbox("cleanupHtml")
            }
            +" cleanup HTML"
          }
        }
        input {
          className = "button".toClassName()
          type = "reset".toInputType()
          value = "Reset"
          id = "post-form-reset-button".toElementId()
        }
        if (images.size < 4) {
          button {
            className = "button".toClassName()
            onClick = { event: MouseEvent<*, *> ->
              event.preventDefault()
              addImageComponent()
            }
            +"Add image"
          }
        }
        input {
          className = "button is-primary".toClassName()
          type = "submit".toInputType()
          value = "Submit"
        }
      }
    }
  }

data class ModalData(
  val type: MessageType,
  val message: String,
  val linkText: String? = null,
  val linkHref: String? = null,
)

val PublishFormPage =
  FC<Props> {
    var modalData by useState<ModalData?>(null)

    modalData?.let { data ->
      ModalMessage {
        type = data.type
        message = data.message
        linkText = data.linkText
        linkHref = data.linkHref
        isEnabled = true
        onDisable = { modalData = null }
      }
    }

    val showModal = { type: MessageType ->
      { message: String, linkText: String?, linkHref: String? ->
        modalData = ModalData(type, message, linkText, linkHref)
      }
    }

    Authorize {
      div {
        className = "publish-form".toClassName()
        section {
          className = "section".toClassName()
          div {
            className = "container block".toClassName()
            h1 {
              className = "title".toClassName()
              +"Social Publish"
            }
            p {
              className = "subtitle".toClassName()
              +"Spam all your social media accounts at once!"
            }
          }

          div {
            className = "container".toClassName()
            PostForm {
              onError = { showModal(MessageType.ERROR)(it, null, null) }
              onInfo = { message, linkText, linkHref ->
                showModal(MessageType.INFO)(message, linkText, linkHref)
              }
            }
          }
        }
      }
    }
  }
