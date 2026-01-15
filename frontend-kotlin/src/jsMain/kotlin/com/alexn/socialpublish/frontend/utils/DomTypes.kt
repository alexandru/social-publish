package com.alexn.socialpublish.frontend.utils

import js.reflect.unsafeCast
import web.cssom.ClassName
import web.dom.ElementId
import web.html.InputType
import web.http.RequestMethod
import web.window.WindowTarget

fun String.toClassName(): ClassName = ClassName(this)

fun String.toElementId(): ElementId = ElementId(this)

fun String.toWindowTarget(): WindowTarget = unsafeCast(this)

fun String.toInputType(): InputType = unsafeCast(this)

fun String.toRequestMethod(): RequestMethod = unsafeCast(this)
