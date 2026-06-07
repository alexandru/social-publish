package socialpublish.backend.common

import kotlin.jvm.optionals.getOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val generatedKotlinClassSuffix = "(Kt)?[$]?$".toRegex()

fun loggerFactory(name: String): Lazy<Logger> = lazy {
    LoggerFactory.getLogger(name)
}

fun loggerFactory(clazz: Class<*>): Lazy<Logger> = lazy {
    LoggerFactory.getLogger(loggerName(clazz))
}

fun loggerFactory(): Lazy<Logger> =
    loggerFactory(callingClass()?.let { loggerName(it) } ?: "Default")

private fun loggerName(clazz: Class<*>): String =
    clazz.name.replace(generatedKotlinClassSuffix, "")

private fun callingClass(): Class<*>? {
    val helperClassNames =
        setOf(
            "socialpublish.backend.common.LoggingKt",
            "socialpublish.backend.common.loggingKt",
        )
    return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        .walk { frames ->
            frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter { it.name !in helperClassNames }
                .findFirst()
                .getOrNull()
        }
}
