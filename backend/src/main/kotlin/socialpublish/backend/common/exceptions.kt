package socialpublish.backend.common

import kotlin.coroutines.cancellation.CancellationException

fun isFatal(e: Throwable): Boolean =
    when (e) {
        is VirtualMachineError,
        is LinkageError -> true
        else -> false
    }

fun isFatalOrCancelled(e: Throwable): Boolean =
    isFatal(e) ||
        when (e) {
            is CancellationException,
            is InterruptedException -> true
            else -> false
        }

/**
 * Catching exceptions such as `CancellationException` or `InterruptedException` is pretty bad,
 * because these should never be caught by mistake, captured in some `Either` datatype or ignored.
 *
 * Ditto for `VirtualMachineError`, because in that case the whole process should crash as fast as
 * possible, or we risk it being left in a zombie state.
 */
fun rethrowIfFatal(e: Throwable) {
    if (isFatal(e)) throw e
}

/** This check also detects `CancellationException` and `InterruptedException`. */
fun rethrowIfFatalOrCancelled(e: Throwable) {
    if (isFatalOrCancelled(e)) throw e
}

/** Runs [finalizer] and rethrows any exceptions that are not fatal in a safe way. */
inline fun rethrowIfFatal(e: Throwable, finalizer: () -> Unit) {
    rethrowIfFatal(e)
    try {
        finalizer()
    } catch (e2: Throwable) {
        rethrowIfFatalOrCancelled(e2)
        e.addSuppressed(e2)
    }
    rethrowIfFatalOrCancelled(e)
}
