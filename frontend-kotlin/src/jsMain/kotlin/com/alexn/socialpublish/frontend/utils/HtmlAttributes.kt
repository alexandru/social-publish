package com.alexn.socialpublish.frontend.utils

import react.dom.html.HTMLAttributes

operator fun HTMLAttributes<*>.get(key: String): String? = asDynamic()[key] as? String

operator fun HTMLAttributes<*>.set(key: String, value: String?) {
  asDynamic()[key] = value
}

var HTMLAttributes<*>.dataTarget: String?
  get() = this["data-target"]
  set(value) {
    this["data-target"] = value
  }
