package com.alexn.socialpublish.frontend.utils

import web.cssom.ClassName
import web.dom.ElementId
import web.html.InputType
import web.http.RequestMethod
import web.window.WindowTarget

fun String.toClassName(): ClassName = ClassName(this)

fun String.toElementId(): ElementId = ElementId(this)

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
fun String.toWindowTarget(): WindowTarget = this.asDynamic() as WindowTarget

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")  
fun String.toInputType(): InputType = this.asDynamic() as InputType

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
fun String.toRequestMethod(): RequestMethod = this.asDynamic() as RequestMethod
