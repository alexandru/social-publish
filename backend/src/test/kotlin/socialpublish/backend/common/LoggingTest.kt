package socialpublish.backend.common

import kotlin.test.Test
import kotlin.test.assertEquals

private val topLevelLogger by loggerFactory()

class LoggingTest {
    private val classLogger by loggerFactory()

    @Test
    fun `no-arg logger factory uses top-level source file class`() {
        assertEquals(
            "socialpublish.backend.common.LoggingTest",
            topLevelLogger.name,
        )
    }

    @Test
    fun `no-arg logger factory uses enclosing class when called in a class`() {
        assertEquals(
            "socialpublish.backend.common.LoggingTest",
            classLogger.name,
        )
    }

    @Test
    fun `class logger factory normalizes Kotlin top-level file classes`() {
        assertEquals(
            "socialpublish.backend.common.LoggingTest",
            loggerFactory(
                    Class.forName("socialpublish.backend.common.LoggingTestKt")
                )
                .value
                .name,
        )
    }
}
