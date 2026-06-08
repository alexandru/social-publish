package socialpublish.backend.server

import kotlinx.serialization.json.Json
import socialpublish.backend.common.jsonCommon

fun serverJson(): Json = Json(jsonCommon) { encodeDefaults = true }
