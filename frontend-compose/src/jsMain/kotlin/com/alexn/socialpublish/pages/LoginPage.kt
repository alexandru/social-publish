package com.alexn.socialpublish.pages

import androidx.compose.runtime.*
import com.alexn.socialpublish.components.ErrorModal
import com.alexn.socialpublish.models.LoginRequest
import com.alexn.socialpublish.models.LoginResponse
import com.alexn.socialpublish.utils.ApiClient
import com.alexn.socialpublish.utils.ApiResponse
import com.alexn.socialpublish.utils.Storage
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.*

// External declaration for URLSearchParams
external class URLSearchParams(init: String = definedExternally) {
    fun get(name: String): String?
}

@Composable
fun LoginPage() {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val handleSubmit: () -> Unit = {
        scope.launch {
            isLoading = true
            try {
                val response =
                    ApiClient.post<LoginResponse, LoginRequest>(
                        "/api/login",
                        LoginRequest(username, password),
                    )

                when (response) {
                    is ApiResponse.Success -> {
                        Storage.setJwtToken(response.data.token)
                        Storage.setAuthStatus(response.data.hasAuth)
                        // Check for redirect query param using URLSearchParams
                        val searchParams = URLSearchParams(window.location.search)
                        val redirect = searchParams.get("redirect")
                        window.location.href = redirect ?: "/"
                    }
                    is ApiResponse.Error -> {
                        error = response.message
                    }
                    is ApiResponse.Exception -> {
                        error = "Exception while logging in: ${response.message}"
                    }
                }
            } catch (e: Exception) {
                error = "Exception while logging in: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    ErrorModal(message = error) { error = null }

    Div(attrs = { classes("login") }) {
        Section(attrs = { classes("section") }) {
            Div(attrs = { classes("container") }) {
                H1(attrs = { classes("title") }) { Text("Login") }

                Form(
                    attrs = {
                        classes("box")
                        addEventListener("submit") { event ->
                            event.preventDefault()
                            handleSubmit()
                        }
                    }
                ) {
                    Div(attrs = { classes("field") }) {
                        Label(attrs = { classes("label") }) { Text("Username") }
                        Div(attrs = { classes("control") }) {
                            Input(
                                type = InputType.Text,
                                attrs = {
                                    classes("input")
                                    value(username)
                                    onInput { username = it.value }
                                    attr("required", "")
                                    if (isLoading) attr("disabled", "")
                                },
                            )
                        }
                    }

                    Div(attrs = { classes("field") }) {
                        Label(attrs = { classes("label") }) { Text("Password") }
                        Div(attrs = { classes("control") }) {
                            Input(
                                type = InputType.Password,
                                attrs = {
                                    classes("input")
                                    value(password)
                                    onInput { password = it.value }
                                    attr("required", "")
                                    if (isLoading) attr("disabled", "")
                                },
                            )
                        }
                    }

                    Button(
                        attrs = {
                            classes("button", "is-primary")
                            attr("type", "submit")
                            if (isLoading) {
                                classes("is-loading")
                                attr("disabled", "")
                            }
                        }
                    ) {
                        Text("Submit")
                    }
                }
            }
        }
    }
}
