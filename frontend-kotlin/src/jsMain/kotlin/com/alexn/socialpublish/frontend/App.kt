package com.alexn.socialpublish.frontend

import com.alexn.socialpublish.frontend.components.NavBar
import com.alexn.socialpublish.frontend.pages.*
import com.alexn.socialpublish.frontend.utils.jso
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import tanstack.react.router.Outlet
import tanstack.react.router.RouterProvider
import tanstack.react.router.createRootRoute
import tanstack.react.router.createRoute
import tanstack.react.router.createRouter
import js.reflect.unsafeCast

val Root = FC<Props> {
    NavBar()
    div {
        Outlet()
    }
}

val rootRoute = createRootRoute(
    jso<dynamic> {
        component = Root
    }
)

val indexRoute = createRoute(
    jso<dynamic> {
        getParentRoute = { rootRoute }
        path = "/"
        component = Home
    }
)

val loginRoute = createRoute(
    jso<dynamic> {
        getParentRoute = { rootRoute }
        path = "login"
        component = Login
        validateSearch = { search: dynamic -> search }
    }
)

val accountRoute = createRoute(
    jso<dynamic> {
        getParentRoute = { rootRoute }
        path = "account"
        component = Account
    }
)

val formRoute = createRoute(
    jso<dynamic> {
        getParentRoute = { rootRoute }
        path = "form"
        component = PublishFormPage
    }
)

val notFoundRoute = createRoute(
    jso<dynamic> {
        getParentRoute = { rootRoute }
        path = "*"
        component = NotFound
    }
)

val routeTree = rootRoute.addChildren(
    arrayOf(
        indexRoute,
        loginRoute,
        accountRoute,
        formRoute,
        notFoundRoute
    )
)

val router = createRouter(
    jso<dynamic> {
        this.routeTree = routeTree
    }
)

val App = FC<Props> {
    RouterProvider {
        this.router = router
    }
}
