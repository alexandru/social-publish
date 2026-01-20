package com.alexn.socialpublish.frontend.components

import com.alexn.socialpublish.frontend.models.MessageType
import com.alexn.socialpublish.frontend.utils.getJwtToken
import com.alexn.socialpublish.frontend.utils.navigateOptionsWithSearch
import com.alexn.socialpublish.frontend.utils.toClassName
import react.FC
import react.PropsWithChildren
import react.dom.html.ReactHTML.div
import react.useState
import tanstack.react.router.useLocation
import tanstack.react.router.useNavigate

val Authorize =
  FC<PropsWithChildren> { props ->
    var message by useState("You are not authorized to view this page. Please log in...")
    val token = getJwtToken()
    val location = useLocation()
    val navigate = useNavigate()
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
          navigate(
            navigateOptionsWithSearch(
              to = "/login",
              searchParams = mapOf("redirect" to "${location.pathname}${location.searchStr}"),
            )
          )
        }
      }
    } else {
      div {
        className = "authorized".toClassName()
        props.children?.let { +it }
      }
    }
  }
