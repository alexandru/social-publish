package socialpublish.backend.utils

import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher

/**
 * A coroutine dispatcher based on Java Virtual Threads (Project Loom) when available (Java 21+).
 *
 * Creates a new virtual thread for each dispatched coroutine, providing lightweight concurrency
 * without the overhead of platform threads.
 *
 * Falls back to Dispatchers.IO for Java versions < 21.
 */
val Dispatchers.LOOM: CoroutineDispatcher
    get() =
        try {
            // Try to use virtual threads if available (Java 21+)
            val startVirtualThread =
                Thread::class.java.getMethod("startVirtualThread", Runnable::class.java)
            object : ExecutorCoroutineDispatcher(), Executor {
                override val executor: Executor
                    get() = this

                override fun close() {
                    error("Cannot be invoked on Dispatchers.LOOM")
                }

                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    startVirtualThread.invoke(null, block)
                }

                override fun execute(command: Runnable) {
                    startVirtualThread.invoke(null, command)
                }

                override fun toString() = "Dispatchers.LOOM"
            }
        } catch (e: NoSuchMethodException) {
            // Virtual threads not available, fall back to IO dispatcher
            Dispatchers.IO
        }
