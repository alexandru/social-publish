package com.alexn.socialpublish.frontend.components

import com.alexn.socialpublish.frontend.utils.navigateOptions
import com.alexn.socialpublish.frontend.utils.linkTo
import com.alexn.socialpublish.frontend.utils.linkClassName
import com.alexn.socialpublish.frontend.icons.homeOutline
import com.alexn.socialpublish.frontend.icons.logIn
import com.alexn.socialpublish.frontend.icons.logOut
import com.alexn.socialpublish.frontend.icons.logoGithub
import com.alexn.socialpublish.frontend.icons.play
import com.alexn.socialpublish.frontend.utils.clearJwtToken
import com.alexn.socialpublish.frontend.utils.dataTarget
import com.alexn.socialpublish.frontend.utils.hasJwtToken
import com.alexn.socialpublish.frontend.utils.toClassName
import com.alexn.socialpublish.frontend.utils.toElementId
import com.alexn.socialpublish.frontend.utils.toWindowTarget
import react.FC
import react.Props
import react.dom.events.MouseEvent
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.strong
import tanstack.react.router.Link
import tanstack.react.router.useNavigate
import react.useState

val NavBar = FC<Props> {
    val (navbarIsActive, setNavbarIsActive) = useState(false)
    
    val isLoggedIn = hasJwtToken()
    val burgerClass = if (navbarIsActive) "navbar-burger is-active" else "navbar-burger"
    val menuClass = if (navbarIsActive) "navbar-menu is-active" else "navbar-menu"

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
                Link {
                    key = "nav-home"
                    className = linkClassName { isActive ->
                        if (isActive) "navbar-item is-active" else "navbar-item"
                    }
                    to = linkTo("/")
                    span {
                        className = "icon".toClassName()
                        img { src = homeOutline; alt = "Home" }
                    }
                    strong { +"Home" }
                }
                if (isLoggedIn) {
                    Link {
                        key = "nav-form"
                        className = linkClassName { isActive ->
                            if (isActive) "navbar-item is-active" else "navbar-item"
                        }
                        to = linkTo("/form")
                        span {
                            className = "icon".toClassName()
                            img { src = play; alt = "Publish" }
                        }
                        strong { +"Publish" }
                    }
                }
                a {
                    key = "nav-help"
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
                            Link {
                                className = linkClassName { isActive ->
                                    if (isActive) "button is-primary is-active" else "button is-primary"
                                }
                                to = linkTo("/account")
                                strong { +"Account" }
                            }
                        }
                        LoginOrLogoutButton {
                            key = if (isLoggedIn) "btn-logout" else "btn-login"
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
    val navigate = useNavigate()
    val onLogout = { event: MouseEvent<*, *> ->
        event.preventDefault()
        clearJwtToken()
        navigate(navigateOptions("/"))
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
        Link {
            key = "cta-login"
            className = linkClassName { isActive ->
                if (isActive) "button is-primary is-active" else "button is-primary"
            }
            to = linkTo("/login")
            span {
                className = "icon".toClassName()
                img { src = logIn; alt = "Login" }
            }
            strong { +"Login" }
        }
    }
}
