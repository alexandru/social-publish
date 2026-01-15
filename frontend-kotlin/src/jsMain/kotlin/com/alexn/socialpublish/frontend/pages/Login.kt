@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.alexn.socialpublish.frontend.pages

import com.alexn.socialpublish.frontend.components.ModalMessage
import com.alexn.socialpublish.frontend.models.MessageType
import com.alexn.socialpublish.frontend.utils.HasAuth
import com.alexn.socialpublish.frontend.utils.navigateTo
import com.alexn.socialpublish.frontend.utils.setAuthStatus
import com.alexn.socialpublish.frontend.utils.setJwtToken
import com.alexn.socialpublish.frontend.utils.toClassName
import com.alexn.socialpublish.frontend.utils.toElementId
import com.alexn.socialpublish.frontend.utils.toInputType
import com.alexn.socialpublish.frontend.utils.toRequestMethod
import com.alexn.socialpublish.frontend.utils.useCurrentPath
import js.promise.await
import kotlin.js.JSON
import kotlin.js.json
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
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
import web.console.console
import web.html.HTMLFormElement
import web.html.HTMLInputElement
import web.html.InputType
import web.http.BodyInit
import web.http.Headers
import web.http.RequestInit
import web.http.RequestMethod
import web.http.fetch
import web.url.URLSearchParams
import web.window.window


val Login = FC<Props> {
    var username by useState("")
    var password by useState("")
    var error by useState<String?>(null)

    val currentPath = useCurrentPath()
    val query = URLSearchParams(window.location.search)
    val redirectTo = query.get("redirect") ?: "/form"
    val scope = useMemo { MainScope() }

    useEffect(dependencies = arrayOf(currentPath)) {
        val errorCode = query.get("error")
        if (errorCode != null) {
            error = when (errorCode) {
                "401" -> "Unauthorized! Please log in..."
                else -> "Forbidden! Please log in..."
            }
        }
    }


    val hideError = {
        if (query.get("error") != null) {
            query.delete("error")
            navigateTo("/login?${'$'}{query.toString()}")
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
                val body = response.jsonAsync().await()
                if (response.status == 200.toShort()) {
                    val token = body.asDynamic().token as? String
                    if (token == null) {
                        error = "No token received from the server!"
                    } else {
                        val hasAuth = body.asDynamic().hasAuth
                        val twitter = hasAuth?.twitter as? Boolean ?: false
                        setJwtToken(token)
                        setAuthStatus(HasAuth(twitter = twitter))
                        navigateTo(redirectTo)
                    }
                } else if (body.asDynamic().error != null) {
                    error = "${'$'}{body.asDynamic().error}!"
                } else {
                    console.warn("Error while logging in:", response.status, body)
                    error = "HTTP ${'$'}{response.status} error while logging in! "
                }
            } catch (exception: dynamic) {
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
