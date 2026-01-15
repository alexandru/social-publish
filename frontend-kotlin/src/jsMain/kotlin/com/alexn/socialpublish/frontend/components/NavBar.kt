package com.alexn.socialpublish.frontend.components

import com.alexn.socialpublish.frontend.icons.homeOutline
import com.alexn.socialpublish.frontend.icons.logIn
import com.alexn.socialpublish.frontend.icons.logOut
import com.alexn.socialpublish.frontend.icons.logoGithub
import com.alexn.socialpublish.frontend.icons.play
import com.alexn.socialpublish.frontend.utils.clearJwtToken
import com.alexn.socialpublish.frontend.utils.dataTarget
import com.alexn.socialpublish.frontend.utils.hasJwtToken
import com.alexn.socialpublish.frontend.utils.navigateTo
import com.alexn.socialpublish.frontend.utils.toClassName
import com.alexn.socialpublish.frontend.utils.toElementId
import com.alexn.socialpublish.frontend.utils.toWindowTarget
import com.alexn.socialpublish.frontend.utils.useCurrentPath
import react.FC
import react.Props
import react.dom.events.MouseEvent
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.strong
import react.useState

val NavBar = FC<Props> {
    val (navbarIsActive, setNavbarIsActive) = useState(false)
    val pathname = useCurrentPath().substringBefore("?")

    fun classOfLink(mainClass: String, path: String): String =
        if (pathname == path) "${'$'}mainClass is-active" else mainClass

    val isLoggedIn = hasJwtToken()
    val burgerClass = if (navbarIsActive) "navbar-burger is-active" else "navbar-burger"
    val menuClass = if (navbarIsActive) "navbar-menu is-active" else "navbar-menu"

    val onNavigate = { path: String ->
        { event: MouseEvent<*, *> ->
            event.preventDefault()
            navigateTo(path)
        }
    }

    nav {
        className = "navbar is-primary".toClassName()
        ariaLabel = "main navigation"

        div {
            className = "navbar-brand".toClassName()
            a {
                className = burgerClass.toClassName()
                ariaLabel = "menu"
                ariaExpanded = navbarIsActive
                dataTarget = "navbarBasicExample"
                onClick = {
                    setNavbarIsActive(!navbarIsActive)
                }
                span { ariaHidden = true }
                span { ariaHidden = true }
                span { ariaHidden = true }
            }
        }

        div {
            id = "navbarBasicExample".toElementId()
            className = menuClass.toClassName()
            div {
                className = "navbar-start".toClassName()
                a {
                    className = classOfLink("navbar-item", "/").toClassName()
                    href = "/"
                    onClick = onNavigate("/")
                    span {
                        className = "icon".toClassName()
                        img { src = homeOutline; alt = "Home" }
                    }
                    strong { +"Home" }
                }
                if (isLoggedIn) {
                    a {
                        className = classOfLink("navbar-item", "/form").toClassName()
                        href = "/form"
                        onClick = onNavigate("/form")
                        span {
                            className = "icon".toClassName()
                            img { src = play; alt = "Publish" }
                        }
                        strong { +"Publish" }
                    }
                }
                a {
                    className = "navbar-item".toClassName()
                    href = "https://github.com/alexandru/social-publish"
                    target = "_blank".toWindowTarget()
                    rel = "noreferrer"
                    span {
                        className = "icon".toClassName()
                        img { src = logoGithub; alt = "Help" }
                    }
                    strong { +"Help" }
                }
            }

            div {
                className = "navbar-end".toClassName()
                div {
                    className = "navbar-item".toClassName()
                    div {
                        className = "buttons".toClassName()
                        if (isLoggedIn) {
                            a {
                                className = classOfLink("button is-primary", "/account").toClassName()
                                href = "/account"
                                onClick = onNavigate("/account")
                                strong { +"Account" }
                            }
                        }
                        LoginOrLogoutButton {
                            this.isLoggedIn = isLoggedIn
                        }
                    }
                }
            }
        }
    }
}

external interface LoginOrLogoutButtonProps : Props {
    var isLoggedIn: Boolean
}

val LoginOrLogoutButton = FC<LoginOrLogoutButtonProps> { props ->
    val pathname = useCurrentPath().substringBefore("?")

    val onLogout = { event: MouseEvent<*, *> ->
        event.preventDefault()
        clearJwtToken()
        navigateTo("/")
    }

    if (props.isLoggedIn) {
        a {
            className = "button is-primary".toClassName()
            href = "/"
            onClick = onLogout
            strong { +"Logout" }
            span {
                className = "icon".toClassName()
                img { src = logOut; alt = "Logout" }
            }
        }
    } else {
        val status = if (pathname == "/login") " is-active" else ""
        a {
            className = "button is-primary${'$'}status".toClassName()
            href = "/login"
            onClick = { event ->
                event.preventDefault()
                navigateTo("/login")
            }
            span {
                className = "icon".toClassName()
                img { src = logIn; alt = "Login" }
            }
            strong { +"Login" }
        }
    }
}
