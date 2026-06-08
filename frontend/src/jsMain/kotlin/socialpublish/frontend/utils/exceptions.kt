package socialpublish.frontend.utils

import kotlinx.coroutines.CancellationException

fun rethrowIfFatal(e: Throwable) {
    if (e is CancellationException) throw e
}
