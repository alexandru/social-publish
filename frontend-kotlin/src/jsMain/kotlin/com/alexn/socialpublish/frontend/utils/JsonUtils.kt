package com.alexn.socialpublish.frontend.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

fun parseJsonObject(text: String): JsonObject? {
  return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
}
