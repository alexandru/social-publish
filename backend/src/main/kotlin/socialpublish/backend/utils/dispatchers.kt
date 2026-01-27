package socialpublish.backend.utils

import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher

/**
 * A coroutine dispatcher based on Java Virtual Threads (Project Loom).
 *
 * Creates a new virtual thread for each dispatched coroutine, providing lightweight concurrency
 * without the overhead of platform threads.
 *
 * Requires Java 21 or later.
 */
val Dispatchers.LoomIO: CoroutineDispatcher
    get() = LOOM_DISPATCHER

private val LOOM_DISPATCHER: CoroutineDispatcher
    get() =
        object : ExecutorCoroutineDispatcher(), Executor {
            override val executor: Executor
                get() = this

            override fun close() {
                error("Cannot be invoked on Dispatchers.LOOM")
            }

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Thread.startVirtualThread(block)
            }

            override fun execute(command: Runnable) {
                Thread.startVirtualThread(command)
            }

            override fun toString() = "Dispatchers.LOOM"
        }
