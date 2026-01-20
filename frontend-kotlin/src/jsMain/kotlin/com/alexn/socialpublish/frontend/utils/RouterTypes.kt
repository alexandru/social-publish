package com.alexn.socialpublish.frontend.utils

import tanstack.router.core.NavigateOptions as TanStackNavigateOptions
import tanstack.router.core.RoutePath

// Type-safe wrappers for tanstack router navigation

fun navigateOptions(to: String): TanStackNavigateOptions = jso { this.to = RoutePath(to) }

fun navigateOptionsWithSearch(
  to: String,
  searchParams: Map<String, String>,
): TanStackNavigateOptions = jso {
  this.to = RoutePath(to)
  this.search = jso { searchParams.forEach { (key, value) -> js("this[key] = value") } }
}

// Link helpers
fun linkTo(path: String): RoutePath = RoutePath(path)
