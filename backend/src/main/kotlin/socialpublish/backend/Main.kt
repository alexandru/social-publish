package socialpublish.backend

import arrow.continuations.SuspendApp
import arrow.fx.coroutines.resourceScope
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.cio.CIO
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import socialpublish.backend.db.CreateResult
import socialpublish.backend.db.Database
import socialpublish.backend.db.DatabaseBundle
import socialpublish.backend.db.UpdateUsernameResult
import socialpublish.backend.db.UsersDatabase
import socialpublish.backend.modules.AuthModule
import socialpublish.backend.modules.FilesConfig
import socialpublish.backend.server.ServerAuthConfig
import socialpublish.backend.server.ServerConfig
import socialpublish.backend.server.startServer

private val logger = KotlinLogging.logger {}

/** Main CLI command that delegates to subcommands. */
class SocialPublishCli : NoOpCliktCommand(name = "social-publish") {
    init {
        subcommands(
            StartServerCommand(),
            GenBcryptHashCommand(),
            ChangePasswordCommand(),
            ChangeUsernameCommand(),
            CreateUserCommand(),
        )
    }
}

/** Subcommand to start the server. */
class StartServerCommand : CliktCommand(name = "start-server") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Start the Social Publish server"

    // Server configuration
    private val dbPath: String by
        option(
                "--db-path",
                help = "Path to the SQLite database file (env: DB_PATH)",
                envvar = "DB_PATH",
            )
            .required()

    private val httpPort: Int by
        option("--http-port", help = "Port to listen on (env: HTTP_PORT)", envvar = "HTTP_PORT")
            .int()
            .default(3000)

    private val baseUrl: String by
        option(
                "--base-url",
                help = "Public URL of this server (env: BASE_URL)",
                envvar = "BASE_URL",
            )
            .required()

    private val staticContentPaths: List<File> by
        option(
                "--static-content-path",
                help = "Path to serve static content from (default: public)",
                envvar = "STATIC_CONTENT_PATH",
            )
            .file(mustExist = false, canBeDir = true, canBeFile = false)
            .multiple()

    private val serverAuthJwtSecret: String by
        option(
                "--server-auth-jwt-secret",
                help = "JWT secret for server authentication (env: JWT_SECRET)",
                envvar = "JWT_SECRET",
            )
            .required()

    // Files storage configuration
    private val uploadedFilesPath: File by
        option(
                "--uploaded-files-path",
                help = "Directory where uploaded files are stored (env: UPLOADED_FILES_PATH)",
                envvar = "UPLOADED_FILES_PATH",
            )
            .file(mustExist = false, canBeDir = true, canBeFile = false)
            .required()

    override fun run() {
        val serverAuthConfig = ServerAuthConfig(jwtSecret = serverAuthJwtSecret)

        val serverConfig =
            ServerConfig(
                dbPath = dbPath,
                httpPort = httpPort,
                baseUrl = baseUrl,
                staticContentPaths = staticContentPaths,
                auth = serverAuthConfig,
            )

        val filesConfig = FilesConfig(uploadedFilesPath = uploadedFilesPath, baseUrl = baseUrl)

        val config = AppConfig(server = serverConfig, files = filesConfig)

        // SuspendApp currently has issues with System.exit, hence logic above cannot
        // be inside SuspendApp

        SuspendApp {
            resourceScope {
                logger.info { "Starting the Social Publish backend..." }
                logger.info { "Using database path: ${config.server.dbPath}" }
                logger.info {
                    "Serving static content from: ${config.server.staticContentPaths.joinToString(", ")}"
                }
                try {
                    val resources = DatabaseBundle.resource(config.server.dbPath).bind()

                    logger.info { "Database initialized successfully" }
                    val _ =
                        startServer(
                                config,
                                resources.documentsDb,
                                resources.postsDb,
                                resources.filesDb,
                                resources.usersDb,
                                engine = CIO,
                            )
                            .bind()

                    logger.info { "Server running on port ${config.server.httpPort}" }
                    awaitCancellation()
                } catch (e: Exception) {
                    logger.error(e) { "Application failed to start" }
                    throw e
                }
            }
        }
    }
}

/** Subcommand to generate a BCrypt hash for a password. */
class GenBcryptHashCommand : CliktCommand(name = "gen-bcrypt-hash") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Generate a BCrypt hash for a password"

    private val verbose by option("--verbose", "-v", help = "Enable verbose logging").flag()

    private val quiet by
        option("--quiet", "-q", help = "Only output the hash without any messages").flag()

    private val password by
        option("--password", "-p", help = "Password to hash (will prompt if not provided)")
            .prompt("Enter password", hideInput = true, requireConfirmation = false)

    override fun run() {
        socialpublish.backend.common.LoggingConfig.configureForCliCommand(verbose)

        val hash = AuthModule.hashPassword(password)

        if (quiet) {
            echo(hash)
        } else {
            echo()
            echo("BCrypt hash:")
            echo(hash)
            echo()
        }
    }
}

/** Subcommand to change a user's password in the database. */
class ChangePasswordCommand : CliktCommand(name = "change-password") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Change a user's password in the database"

    private val verbose by option("--verbose", "-v", help = "Enable verbose logging").flag()

    private val dbPath: String by
        option(
                "--db-path",
                help = "Path to the SQLite database file (env: DB_PATH)",
                envvar = "DB_PATH",
            )
            .required()

    private val username by
        option("--username", "-u", help = "Username of the account to update")
            .prompt("Enter username")

    private val newPassword by
        option("--new-password", "-p", help = "New password (will prompt if not provided)")
            .prompt("Enter new password", hideInput = true, requireConfirmation = true)

    override fun run() {
        socialpublish.backend.common.LoggingConfig.configureForCliCommand(verbose)

        runBlocking {
            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                when (val result = usersDb.updatePassword(username, newPassword)) {
                    is arrow.core.Either.Left -> {
                        echo("Error changing password: ${result.value.message}", err = true)
                        throw ProgramResult(1)
                    }
                    is arrow.core.Either.Right -> {
                        if (result.value) {
                            echo()
                            echo("✓ Password changed successfully for user '$username'")
                            echo()
                        } else {
                            echo("Error: User '$username' not found", err = true)
                            throw ProgramResult(1)
                        }
                    }
                }
            }
        }
    }
}

/** Subcommand to change a user's username in the database. */
class ChangeUsernameCommand : CliktCommand(name = "change-username") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Change a user's username in the database"

    private val verbose by option("--verbose", "-v", help = "Enable verbose logging").flag()

    private val dbPath: String by
        option(
                "--db-path",
                help = "Path to the SQLite database file (env: DB_PATH)",
                envvar = "DB_PATH",
            )
            .required()

    private val currentUsername by
        option("--current-username", "-u", help = "Current username of the account")
            .prompt("Enter current username")

    private val newUsername by
        option("--new-username", "-n", help = "New username for the account")
            .prompt("Enter new username")

    override fun run() {
        socialpublish.backend.common.LoggingConfig.configureForCliCommand(verbose)

        runBlocking {
            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                when (val result = usersDb.updateUsername(currentUsername, newUsername)) {
                    is arrow.core.Either.Left -> {
                        echo("Error changing username: ${result.value.message}", err = true)
                        throw ProgramResult(1)
                    }
                    is arrow.core.Either.Right -> {
                        when (result.value) {
                            is UpdateUsernameResult.Success -> {
                                echo()
                                echo(
                                    "✓ Username changed successfully from '$currentUsername' to '$newUsername'"
                                )
                                echo()
                            }
                            is UpdateUsernameResult.UserNotFound -> {
                                echo("Error: User '$currentUsername' not found", err = true)
                                throw ProgramResult(1)
                            }
                            is UpdateUsernameResult.UsernameAlreadyExists -> {
                                echo("Error: Username '$newUsername' already exists", err = true)
                                throw ProgramResult(1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Subcommand to create a new user. */
class CreateUserCommand : CliktCommand(name = "create-user") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Create a new user account"

    private val verbose by option("--verbose", "-v", help = "Enable verbose logging").flag()

    private val dbPath: String by
        option(
                "--db-path",
                help = "Path to the SQLite database file (env: DB_PATH)",
                envvar = "DB_PATH",
            )
            .required()

    private val username by
        option("--username", "-u", help = "Username for the new user").prompt("Enter username")

    private val password by
        option("--password", "-p", help = "Password for the new user (will prompt if not provided)")
            .prompt("Enter password", hideInput = true, requireConfirmation = true)

    override fun run() {
        socialpublish.backend.common.LoggingConfig.configureForCliCommand(verbose)

        runBlocking {
            resourceScope {
                val db = Database.connect(dbPath).bind()
                val usersDb = UsersDatabase(db)

                when (val result = usersDb.createUser(username = username, password = password)) {
                    is arrow.core.Either.Left -> {
                        echo("Error creating user: ${result.value.message}", err = true)
                        throw ProgramResult(1)
                    }
                    is arrow.core.Either.Right -> {
                        when (val createResult = result.value) {
                            is CreateResult.Created -> {
                                echo()
                                echo("✓ User created successfully!")
                                echo("  Username: ${createResult.value.username}")
                                echo("  User ID:  ${createResult.value.uuid}")
                                echo("  Created:  ${createResult.value.createdAt}")
                                echo()
                            }
                            is CreateResult.Duplicate -> {
                                echo(
                                    "Error creating user: User with username '$username' already exists",
                                    err = true,
                                )
                                throw ProgramResult(1)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    SocialPublishCli().main(args)
}
