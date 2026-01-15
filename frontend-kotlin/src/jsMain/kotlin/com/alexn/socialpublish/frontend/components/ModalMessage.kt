package com.alexn.socialpublish.frontend.components

import com.alexn.socialpublish.frontend.models.MessageType
import com.alexn.socialpublish.frontend.utils.toClassName
import com.alexn.socialpublish.frontend.utils.toElementId
import com.alexn.socialpublish.frontend.utils.toWindowTarget
import react.FC
import react.Props
import react.dom.events.KeyboardEvent
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.article
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p

external interface ModalMessageProps : Props {
    var type: MessageType
    var message: String
    var isEnabled: Boolean
    var onDisable: () -> Unit
    var linkText: String?
    var linkHref: String?
}

val ModalMessage = FC<ModalMessageProps> { props ->
    val handleClick = {
        props.onDisable()
    }

    val handleKeyDown: (KeyboardEvent<*>) -> Unit = { event ->
        if (event.key == "Escape") {
            props.onDisable()
        }
    }

    val (title, clsType) = when (props.type) {
        MessageType.INFO -> "Info" to "is-info"
        MessageType.WARNING -> "Warning" to "is-warning"
        MessageType.ERROR -> "Error" to "is-danger"
    }

    val modalClass = if (props.isEnabled) "modal is-active" else "modal"

    div {
        id = "message-modal".toElementId()
        className = modalClass.toClassName()

        div {
            className = "modal-background".toClassName()
            onClick = { handleClick() }
            onKeyDown = { event -> handleKeyDown(event) }
        }

        div {
            className = "modal-content".toClassName()
            article {
                className = "message is-medium ${'$'}clsType".toClassName()
                div {
                    className = "message-header".toClassName()
                    p { +title }
                    button {
                        className = "delete".toClassName()
                        ariaLabel = "delete"
                        onClick = { handleClick() }
                    }
                }
                div {
                    className = "message-body".toClassName()
                    p { +props.message }
                    if (props.linkText != null && props.linkHref != null) {
                        p {
                            a {
                                href = props.linkHref
                                target = "_blank".toWindowTarget()
                                +props.linkText!!
                            }
                        }
                    }
                }
            }
        }

        button {
            className = "modal-close is-large".toClassName()
            ariaLabel = "close"
            onClick = { handleClick() }
        }
    }
}
