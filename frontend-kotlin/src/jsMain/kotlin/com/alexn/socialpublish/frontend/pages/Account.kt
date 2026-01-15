@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.alexn.socialpublish.frontend.pages

import com.alexn.socialpublish.frontend.components.Authorize
import com.alexn.socialpublish.frontend.icons.logoTwitter
import com.alexn.socialpublish.frontend.utils.navigateTo
import com.alexn.socialpublish.frontend.utils.toClassName
import com.alexn.socialpublish.frontend.utils.toElementId
import com.alexn.socialpublish.frontend.utils.updateAuthStatus
import js.promise.await
import kotlin.js.Date
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
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
import react.useEffect
import react.useMemo
import react.useState

val Account = FC<Props> {
    var twitterStatus by useState("Querying...")
    val scope = useMemo { MainScope() }

    val authorizeTwitter = {
        window.location.href = "/api/twitter/authorize"
    }

    useEffect(dependencies = emptyArray()) {
        scope.launch {
            val response = fetch("/api/twitter/status")
            if (response.status == 401.toShort() || response.status == 403.toShort()) {
                navigateTo("/login?error=${'$'}{response.status}&redirect=/account")
                return@launch
            }
            if (response.status == 200.toShort()) {
                val json = response.jsonAsync().await()
                val hasAuthorization = json.asDynamic().hasAuthorization as? Boolean ?: false
                if (hasAuthorization) {
                    val createdAt = json.asDynamic().createdAt as? String
                    val atDateTime = if (createdAt != null) " at ${'$'}{Date(createdAt).toLocaleString()}" else ""
                    twitterStatus = "Connected${'$'}atDateTime"
                } else {
                    twitterStatus = "Not connected"
                }
                updateAuthStatus { current -> current.copy(twitter = hasAuthorization) }
                return@launch
            }
            twitterStatus = "Error: HTTP ${'$'}{response.status}"
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
