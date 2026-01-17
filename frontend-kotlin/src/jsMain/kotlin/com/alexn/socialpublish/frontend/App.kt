package com.alexn.socialpublish.frontend

import com.alexn.socialpublish.frontend.components.NavBar
import com.alexn.socialpublish.frontend.pages.*
import com.alexn.socialpublish.frontend.utils.rootRouteOptions
import com.alexn.socialpublish.frontend.utils.routeOptions
import com.alexn.socialpublish.frontend.utils.routerOptions
import react.FC
import react.Props
import react.dom.html.ReactHTML.main
import react.useMemo
import tanstack.react.router.Outlet
import tanstack.react.router.Router
import tanstack.react.router.RouterProvider
import tanstack.react.router.createRootRoute
import tanstack.react.router.createRoute
import tanstack.react.router.createRouter
import tanstack.router.core.RoutePath

private val ROOT_PATH: RoutePath = RoutePath("/")
private val FORM_PATH: RoutePath = RoutePath("/form")
private val LOGIN_PATH: RoutePath = RoutePath("/login")
private val ACCOUNT_PATH: RoutePath = RoutePath("/account")

val Root = FC<Props> {
    NavBar()
    main {
        Outlet()
    }
}

private fun createAppRouter(): Router {
    val rootRoute = createRootRoute(
        options = rootRouteOptions(
            component = Root,
            notFoundComponent = NotFound
        )
    )

    val indexRoute = createRoute(
        options = routeOptions(
            getParentRoute = { rootRoute },
            path = ROOT_PATH,
            component = Home
        )
    )

    val loginRoute = createRoute(
        options = routeOptions(
            getParentRoute = { rootRoute },
            path = LOGIN_PATH,
            component = Login,
            validateSearch = { search -> search }
        )
    )

    val accountRoute = createRoute(
        options = routeOptions(
            getParentRoute = { rootRoute },
            path = ACCOUNT_PATH,
            component = Account
        )
    )

    val formRoute = createRoute(
        options = routeOptions(
            getParentRoute = { rootRoute },
            path = FORM_PATH,
            component = PublishFormPage
        )
    )

    rootRoute.addChildren(
        arrayOf(
            indexRoute,
            loginRoute,
            accountRoute,
            formRoute,
        ),
    )

    return createRouter(
        options = routerOptions(routeTree = rootRoute)
    )
}

val App = FC<Props> {
    val appRouter = useMemo { createAppRouter() }

    RouterProvider {
        router = appRouter
    }
}
