package socialpublish.backend.common

import kotlinx.serialization.json.Json

val jsonCommon = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}
