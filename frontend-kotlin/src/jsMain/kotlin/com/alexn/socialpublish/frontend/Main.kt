package com.alexn.socialpublish.frontend

import react.create
import com.alexn.socialpublish.frontend.utils.toElementId
import react.create
import react.dom.client.createRoot
import web.dom.document

@JsName("require")
external fun jsRequire(name: String): dynamic

fun main() {
    jsRequire("./style.css")
    val container = document.getElementById("app".toElementId()) ?: error("Couldn't find app container!")
    createRoot(container).render(App.create())
}
