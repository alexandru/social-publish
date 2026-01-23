package socialpublish.backend.utils

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.options.required
import socialpublish.backend.modules.AuthModule
import socialpublish.backend.server.ServerAuthConfig

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
        val authModule =
            AuthModule(
                ServerAuthConfig(
                    username = "dummy",
                    passwordHash = serverAuthPassword.trim(),
                    jwtSecret = "dummy",
                )
            )

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
