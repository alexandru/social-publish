package socialpublish.backend.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory

object LoggingConfig {
    /**
     * Set the root logging level programmatically.
     *
     * @param level The logging level (e.g., "ERROR", "WARN", "INFO", "DEBUG")
     */
    fun setRootLogLevel(level: String) {
        val logLevel = Level.toLevel(level, Level.INFO)
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        rootLogger.level = logLevel
    }

    /**
     * Configure logging for CLI commands (quieter, only warnings and errors by default).
     *
     * @param verbose If true, use INFO level; otherwise use WARN level
     */
    fun configureForCliCommand(verbose: Boolean = false) {
        setRootLogLevel(if (verbose) "INFO" else "WARN")
    }
}
