package com.alexn.socialpublish.frontend.utils

import react.FC
import react.Props
import tanstack.router.core.RoutePath
import tanstack.react.router.RouterOptions as TanStackRouterOptions
import tanstack.react.router.RouteOptions as TanStackRouteOptions
import tanstack.react.router.RootRouteOptions as TanStackRootRouteOptions

// Type-safe route option helpers using asDynamic to bypass val restrictions

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
fun rootRouteOptions(
    component: FC<Props>? = null,
    notFoundComponent: FC<Props>? = null
): TanStackRootRouteOptions {
    val obj = jso<Any> {}
    val dyn = obj.asDynamic()
    if (component != null) dyn.component = component
    if (notFoundComponent != null) dyn.notFoundComponent = notFoundComponent
    return obj as TanStackRootRouteOptions
}

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
fun routeOptions(
    getParentRoute: () -> Any,
    path: RoutePath? = null,
    component: FC<Props>? = null,
    validateSearch: ((Any) -> Any)? = null
): TanStackRouteOptions {
    val obj = jso<Any> {}
    val dyn = obj.asDynamic()
    dyn.getParentRoute = getParentRoute
    if (path != null) dyn.path = path
    if (component != null) dyn.component = component
    if (validateSearch != null) dyn.validateSearch = validateSearch
    return obj as TanStackRouteOptions
}

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
fun routerOptions(routeTree: Any): TanStackRouterOptions {
    val obj = jso<Any> {}
    val dyn = obj.asDynamic()
    dyn.routeTree = routeTree
    return obj as TanStackRouterOptions
}
