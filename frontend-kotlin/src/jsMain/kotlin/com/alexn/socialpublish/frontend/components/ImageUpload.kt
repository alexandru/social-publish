package com.alexn.socialpublish.frontend.components

import com.alexn.socialpublish.frontend.models.SelectedImage
import com.alexn.socialpublish.frontend.utils.toClassName
import com.alexn.socialpublish.frontend.utils.toElementId
import com.alexn.socialpublish.frontend.utils.toInputType
import react.FC
import react.Props
import react.dom.events.FormEvent
import react.dom.events.MouseEvent
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.i
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import web.html.HTMLInputElement
import web.html.HTMLTextAreaElement

external interface ImageUploadProps : Props {
    var id: Int
    var state: SelectedImage
    var onSelect: (SelectedImage) -> Unit
    var onRemove: (Int) -> Unit
}

val ImageUpload = FC<ImageUploadProps> { props ->
    val onFileChange: (FormEvent<*>) -> Unit = { event ->
        event.preventDefault()
        val target = event.target as? HTMLInputElement
        val file = target?.files?.item(0)
        props.onSelect(props.state.copy(file = file))
    }

    val onAltTextChange: (FormEvent<*>) -> Unit = { event ->
        event.preventDefault()
        val target = event.target as? HTMLTextAreaElement
        val value = target?.value ?: ""
        props.onSelect(props.state.copy(altText = value))
    }

    val onRemove: (MouseEvent<*, *>) -> Unit = { event ->
        event.preventDefault()
        props.onRemove(props.id)
    }

    div {
        className = "block".toClassName()
        div {
            className = "field".toClassName()
            label {
                className = "label".toClassName()
                +"Image (${props.id})"
            }
            div {
                className = "file".toClassName()
                label {
                    className = "file-label".toClassName()
                    input {
                        id = "file_${'$'}{props.id}".toElementId()
                        name = "file_${'$'}{props.id}"
                        className = "file-input".toClassName()
                        type = "file".toInputType()
                        accept = "image/*"
                        onInput = onFileChange
                    }
                    span {
                        className = "file-cta".toClassName()
                        span {
                            className = "file-icon".toClassName()
                            i { className = "fas fa-upload".toClassName() }
                        }
                        span {
                            className = "file-label".toClassName()
                            +"Choose an image file…"
                        }
                    }
                }
            }
            div { p { className = "help".toClassName(); +"The image will be displayed in the post." } }
            div { span { +(props.state.file?.name ?: "") } }
        }
        div {
            className = "field".toClassName()
            label {
                className = "label".toClassName()
                +"Alt text for image (${props.id})"
            }
            div {
                className = "control".toClassName()
                textarea {
                    id = "altText_${'$'}{props.id}".toElementId()
                    name = "altText_${'$'}{props.id}"
                    rows = 2
                    cols = 50
                    onInput = onAltTextChange
                    value = props.state.altText ?: ""
                    className = "textarea".toClassName()
                }
            }
        }
        div {
            className = "field".toClassName()
            button {
                className = "button is-small".toClassName()
                onClick = onRemove
                +"Remove"
            }
        }
    }
}
