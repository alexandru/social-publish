import com.alexn.socialpublish.frontend.LoginSearch
import com.alexn.socialpublish.frontend.components.Authorize
import com.alexn.socialpublish.frontend.utils.jso
import com.alexn.socialpublish.frontend.icons.logoTwitter
import com.alexn.socialpublish.frontend.utils.parseJsonObject
import com.alexn.socialpublish.frontend.utils.toClassName
import com.alexn.socialpublish.frontend.utils.toElementId
import com.alexn.socialpublish.frontend.utils.updateAuthStatus
import js.promise.await
import js.reflect.unsafeCast
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Date
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import web.http.fetch
import web.window.window

import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.section
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.strong
import tanstack.react.router.useNavigate
import react.useEffect
import react.useMemo
import react.useState

val Account = FC<Props> {
    var twitterStatus by useState("Querying...")
    val scope = useMemo { MainScope() }
    val navigate = useNavigate()

    val authorizeTwitter = {
        window.location.href = "/api/twitter/authorize"
    }

    useEffect(dependencies = emptyArray()) {
        scope.launch {
            val response = fetch("/api/twitter/status")
            if (response.status == 401.toShort() || response.status == 403.toShort()) {
                navigate(
                    jso {
                        to = "/login".unsafeCast<Nothing>()
                        search = jso<LoginSearch> {
                            error = "${response.status}"
                            redirect = "/account"
                        }.unsafeCast<Nothing>()
                    }
                )
                return@launch
            }
            if (response.status == 200.toShort()) {
                val bodyText = response.textAsync().await()
                val bodyJson = parseJsonObject(bodyText)
                val hasAuthorization = bodyJson?.get("hasAuthorization")?.jsonPrimitive?.booleanOrNull ?: false
                if (hasAuthorization) {
                    val createdAt = bodyJson?.get("createdAt")?.jsonPrimitive?.contentOrNull
                    val atDateTime = if (createdAt != null) " at ${Date(createdAt).toLocaleString()}" else ""
                    twitterStatus = "Connected$atDateTime"
                } else {
                    twitterStatus = "Not connected"
                }
                updateAuthStatus { current -> current.copy(twitter = hasAuthorization) }
                return@launch
            }
            twitterStatus = "Error: HTTP ${response.status}"
        }
    }

    Authorize {
        div {
            className = "account".toClassName()
            id = "account".toElementId()
            section {
                className = "section".toClassName()
                div {
                    className = "container block".toClassName()
                    h1 { className = "title".toClassName(); +"Account Settings" }
                }

                div {
                    className = "box".toClassName()
                    h2 { className = "subtitle".toClassName(); +"Social Accounts" }
                    button {
                        className = "button is-link".toClassName()
                        onClick = { authorizeTwitter() }
                        span {
                            className = "icon".toClassName()
                            img {
                                src = logoTwitter
                                alt = ""
                            }
                        }
                        strong { +"Connect X (Twitter)" }
                    }
                    p { className = "help".toClassName(); +twitterStatus }
                }
            }
        }
    }
}
