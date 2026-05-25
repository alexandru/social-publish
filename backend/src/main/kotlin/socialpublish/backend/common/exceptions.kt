package socialpublish.backend.common

import kotlin.coroutines.cancellation.CancellationException

fun isNonFatal(e: Throwable): Boolean =
    when (e) {
        is VirtualMachineError,
        is LinkageError,
        is CancellationException,
        is InterruptedException -> false
        else -> true
    }

/**
 * Catching exceptions such as `CancellationException` or `InterruptedException` is pretty bad,
 * because these should never be caught by mistake, captured in some `Either` datatype or ignored.
 *
 * Ditto for `VirtualMachineError`, because in that case the whole process should crash as fast as
 * possible, or we risk it being left in a zombie state.
 */
fun rethrowIfFatal(e: Throwable) {
    if (!isNonFatal(e)) throw e
}
