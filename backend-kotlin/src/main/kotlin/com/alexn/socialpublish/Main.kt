package com.alexn.socialpublish

import arrow.continuations.SuspendApp
import arrow.fx.coroutines.resourceScope
import com.alexn.socialpublish.db.Database
import com.alexn.socialpublish.server.startServer
import com.github.ajalt.clikt.core.main
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.awaitCancellation

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    // Parse CLI arguments
    val cliCommand = AppCliCommand()
    cliCommand.main(args)
    val config = cliCommand.config
    // SuspendApp currently has issues with System.exit, hence logic above cannot
    // be inside SuspendApp
    SuspendApp {
        resourceScope {
            logger.info { "Starting the Social Publish backend..." }
            logger.info {
                "Using database path: ${config.server.dbPath}"
            }
            logger.info {
                "Serving static content from: ${config.server.staticContentPaths.joinToString(", ")}"
            }
            try {
                val resources =
                    Database.resourceBundle(config.server.dbPath).bind()

                logger.info { "Database initialized successfully" }
                install(
                    acquire = {
                        val engine =
                            startServer(
                                config,
                                resources.documentsDb,
                                resources.postsDb,
                                resources.filesDb,
                            )
                        engine.start(wait = false)
                        engine
                    },
                    release = { engine, _ ->
                        engine.stop(
                            gracePeriodMillis = 1_000,
                            timeoutMillis = 5_000,
                        )
                    },
                )

                logger.info { "Server running on port ${config.server.httpPort}" }
                awaitCancellation()
            } catch (e: Exception) {
                logger.error(e) { "Application failed to start" }
                throw e
            }
        }
    }
}
