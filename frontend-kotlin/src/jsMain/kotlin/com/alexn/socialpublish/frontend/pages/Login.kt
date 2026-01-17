@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.alexn.socialpublish.frontend.pages

import com.alexn.socialpublish.frontend.components.ModalMessage
import com.alexn.socialpublish.frontend.utils.navigateOptions
import com.alexn.socialpublish.frontend.models.MessageType
import com.alexn.socialpublish.frontend.utils.HasAuth
import com.alexn.socialpublish.frontend.utils.setAuthStatus
import com.alexn.socialpublish.frontend.utils.setJwtToken
import com.alexn.socialpublish.frontend.utils.parseJsonObject
import com.alexn.socialpublish.frontend.utils.toClassName
import com.alexn.socialpublish.frontend.utils.toElementId
import com.alexn.socialpublish.frontend.utils.toInputType
import com.alexn.socialpublish.frontend.utils.toRequestMethod
import js.promise.await
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.JSON
import kotlin.js.json
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.events.FormEvent
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.form
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.section
import react.useEffect
import react.useMemo
import react.useState
import tanstack.react.router.useLocation
import tanstack.react.router.useNavigate
import web.console.console
import web.html.HTMLFormElement
import web.html.HTMLInputElement
import web.http.BodyInit
import web.http.Headers
import web.http.RequestInit
import web.http.fetch
import web.url.URLSearchParams

val Login = FC<Props> {
    var username by useState("")
    var password by useState("")
    var error by useState<String?>(null)

    val location = useLocation()
    val navigate = useNavigate()

    val searchParams = URLSearchParams(location.searchStr)
    val redirectTo = searchParams.get("redirect") ?: "/form"
    val errorCode = searchParams.get("error")
    val scope = useMemo { MainScope() }

    useEffect(dependencies = arrayOf(errorCode)) {
        if (errorCode != null) {
            error = when (errorCode) {
                "401" -> "Unauthorized! Please log in..."
                else -> "Forbidden! Please log in..."
            }
        }
    }

    val hideError = {
        if (errorCode != null) {
            val cleanParams = URLSearchParams()
            cleanParams.set("redirect", redirectTo)
            val query = cleanParams.toString()
            val target = if (query.isNotBlank()) "/login?$query" else "/login"
            navigate(navigateOptions(target))
        }
        error = null
    }


    val handleSubmit: (FormEvent<HTMLFormElement>) -> Unit = { event ->
        event.preventDefault()
        scope.launch {
            try {
                val payload = json(
                    "username" to username,
                    "password" to password,
                )
                val response = fetch(
                    "/api/login",
                    RequestInit(
                        method = "POST".toRequestMethod(),
                        headers = Headers().apply { set("Content-Type", "application/json") },
                        body = BodyInit(JSON.stringify(payload)),
                    ),
                )
                val bodyText = response.textAsync().await()
                val bodyJson = parseJsonObject(bodyText)
                if (response.status == 200.toShort()) {
                    val token = bodyJson?.get("token")?.jsonPrimitive?.contentOrNull
                    if (token == null) {
                        error = "No token received from the server!"
                    } else {
                        val hasAuth = bodyJson?.get("hasAuth")?.jsonObject
                        val twitter = hasAuth?.get("twitter")?.jsonPrimitive?.booleanOrNull ?: false
                        setJwtToken(token)
                        setAuthStatus(HasAuth(twitter = twitter))
                        val cleanRedirect = if (redirectTo.startsWith("http://localhost:3001")) {
                            redirectTo.removePrefix("http://localhost:3001")
                        } else {
                            redirectTo
                        }
                        navigate(navigateOptions(cleanRedirect))
                    }
                } else {
                    val errorMessage = bodyJson?.get("error")?.jsonPrimitive?.contentOrNull
                    if (errorMessage != null) {
                        error = "$errorMessage!"
                    } else {
                        console.warn("Error while logging in:", response.status, bodyText)
                        error = "HTTP ${response.status} error while logging in! "
                    }
                }
            } catch (exception: Throwable) {
                // In Kotlin/JS, JavaScript errors may not be Kotlin exceptions
                // Catch as Throwable (covers both Kotlin and JS errors)
                console.error("While logging in", exception)
                error = "Exception while logging in, probably a bug, check the console!"
            }
        }
    }

    val onUsernameChange: (FormEvent<HTMLInputElement>) -> Unit = { event ->
        val target = event.target as? HTMLInputElement
        username = target?.value ?: ""
    }

    val onPasswordChange: (FormEvent<HTMLInputElement>) -> Unit = { event ->
        val target = event.target as? HTMLInputElement
        password = target?.value ?: ""
    }

    div {
        className = "login".toClassName()
        id = "login".toElementId()

        ModalMessage {
            type = MessageType.ERROR
            message = error ?: ""
            isEnabled = error != null
            linkText = null
            linkHref = null
            onDisable = hideError
        }

        section {
            className = "section".toClassName()
            div {
                className = "container".toClassName()
                h1 { className = "title".toClassName(); +"Login" }
                form {
                    onSubmit = handleSubmit
                    className = "box".toClassName()
                    div {
                        className = "field".toClassName()
                        label { className = "label".toClassName(); +"Username" }
                        div {
                            className = "control".toClassName()
                            input {
                                className = "input".toClassName()
                                type = "text".toInputType()
                                onInput = onUsernameChange
                                required = true
                            }
                        }
                    }
                    div {
                        className = "field".toClassName()
                        label { className = "label".toClassName(); +"Password" }
                        div {
                            className = "control".toClassName()
                            input {
                                className = "input".toClassName()
                                type = "password".toInputType()
                                onInput = onPasswordChange
                                required = true
                            }
                        }
                    }
                    input {
                        className = "button is-primary".toClassName()
                        type = "submit".toInputType()
                        value = "Submit"
                    }
                }
            }
        }
    }
}
