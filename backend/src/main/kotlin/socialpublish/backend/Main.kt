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
            CheckPasswordCommand(),
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

    private val quiet by
        option("--quiet", "-q", help = "Only output the hash without any messages").flag()

    private val password by
        option("--password", "-p", help = "Password to hash (will prompt if not provided)")
            .prompt("Enter password", hideInput = true, requireConfirmation = false)

    override fun run() {
        val hash = AuthModule.hashPassword(password)

        if (quiet) {
            echo(hash)
        } else {
            echo()
            echo("BCrypt hash:")
            echo(hash)
            echo()
            echo(
                "You can use this hash as the value for SERVER_AUTH_PASSWORD environment variable or --server-auth-password option."
            )
        }
    }
}

/** Subcommand to check a password against the stored BCrypt hash. */
class CheckPasswordCommand : CliktCommand(name = "check-password") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Check if a password matches the stored BCrypt hash"

    private val serverAuthPassword: String by
        option(
                "--server-auth-password",
                help = "BCrypt hash to check against (env: SERVER_AUTH_PASSWORD)",
                envvar = "SERVER_AUTH_PASSWORD",
            )
            .required()

    private val password by
        option("--password", "-p", help = "Password to check (will prompt if not provided)")
            .prompt("Enter password to check", hideInput = true, requireConfirmation = false)

    override fun run() {
        val authModule = AuthModule("dummy")

        // Use reflection to access the private verifyPassword method
        val verifyMethod =
            AuthModule::class
                .java
                .getDeclaredMethod("verifyPassword", String::class.java, String::class.java)
        verifyMethod.isAccessible = true
        val matches =
            verifyMethod.invoke(authModule, password, serverAuthPassword.trim()) as Boolean

        if (matches) {
            echo("✓ Password matches the hash")
        } else {
            echo("✗ Password does NOT match the hash")
            throw ProgramResult(1)
        }
    }
}

/** Subcommand to create a new user. */
class CreateUserCommand : CliktCommand(name = "create-user") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Create a new user account"

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
