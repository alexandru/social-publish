package com.alexn.socialpublish.frontend.components

import com.alexn.socialpublish.frontend.models.MessageType
import com.alexn.socialpublish.frontend.utils.getJwtToken
import com.alexn.socialpublish.frontend.utils.navigateTo
import com.alexn.socialpublish.frontend.utils.toClassName
import com.alexn.socialpublish.frontend.utils.useCurrentPath
import react.FC
import react.PropsWithChildren
import react.dom.html.ReactHTML.div
import react.useState

val Authorize = FC<PropsWithChildren> { props ->
    var message by useState("You are not authorized to view this page. Please log in...")
    val token = getJwtToken()
    val currentPath = useCurrentPath().substringBefore("?")
    val isEnabled = message.isNotBlank()

    if (token == null) {
        ModalMessage {
            type = MessageType.ERROR
            this.message = message
            this.isEnabled = isEnabled
            linkText = null
            linkHref = null
            onDisable = {
                message = ""
                navigateTo("/login?redirect=${'$'}currentPath")
            }
        }
    } else {
        div {
            className = "authorized".toClassName()
            props.children?.let { +it }
        }
    }
}
