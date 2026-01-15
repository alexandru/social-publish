package com.alexn.socialpublish.frontend.utils

inline fun <T : Any> jso(block: T.() -> Unit): T = (js("{}") as T).apply(block)
