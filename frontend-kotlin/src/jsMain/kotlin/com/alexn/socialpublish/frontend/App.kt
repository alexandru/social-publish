package com.alexn.socialpublish.frontend

import com.alexn.socialpublish.frontend.components.NavBar
import com.alexn.socialpublish.frontend.pages.Account
import com.alexn.socialpublish.frontend.pages.Home
import com.alexn.socialpublish.frontend.pages.Login
import com.alexn.socialpublish.frontend.pages.NotFound
import com.alexn.socialpublish.frontend.pages.PublishFormPage
import com.alexn.socialpublish.frontend.utils.useCurrentPath
import react.FC
import react.Props
import react.dom.html.ReactHTML.main

val App = FC<Props> {
    val currentPath = useCurrentPath().substringBefore("?")
    NavBar()
    main {
        when (currentPath) {
            "/" -> Home()
            "/form" -> PublishFormPage()
            "/login" -> Login()
            "/account" -> Account()
            else -> NotFound()
        }
    }
}
