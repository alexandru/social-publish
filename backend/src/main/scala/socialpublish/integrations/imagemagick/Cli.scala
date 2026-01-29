package socialpublish.integrations.imagemagick

import cats.effect.IO
import java.nio.charset.StandardCharsets

case class CommandResult(
  command: String,
  exitCode: Int,
  stdout: String,
  stderr: String
)

class CliCommandException(
  val command: String,
  val exitCode: Int,
  val stdout: String,
  val stderr: String
) extends Exception(
  s"Command `$command` failed with exit code $exitCode.\nSTDOUT: $stdout\nSTDERR: $stderr"
)

object Cli {

  /**
   * Executes a command using ProcessBuilder.
   * This runs the command directly without going through /bin/sh.
   */
  def executeCommand(executable: String, args: String*): IO[CommandResult] = {
    IO.blocking {
      val processBuilder = new ProcessBuilder((executable +: args)*)
      val process = processBuilder.start()

      // Read stdout and stderr concurrently
      val stdoutBytes = process.getInputStream.readAllBytes()
      val stderrBytes = process.getErrorStream.readAllBytes()
      val exitCode = process.waitFor()

      CommandResult(
        command = (executable +: args).mkString(" "),
        exitCode = exitCode,
        stdout = new String(stdoutBytes, StandardCharsets.UTF_8),
        stderr = new String(stderrBytes, StandardCharsets.UTF_8)
      )
    }
  }

  /**
   * Executes a shell command through /bin/sh.
   * Arguments are escaped and joined with spaces.
   */
  def executeShellCommand(command: String, args: String*): IO[CommandResult] = {
    val escapedArgs = (command +: args).map(escapeShellArg)
    val shellCommand = escapedArgs.mkString(" ")
    executeCommand("/bin/sh", "-c", shellCommand)
  }

  /**
   * Checks if the result has a successful exit code (0).
   * Returns Right(stdout) on success, Left(exception) on failure.
   */
  def orError(result: CommandResult): Either[CliCommandException, String] = {
    if result.exitCode == 0 then {
      Right(result.stdout)
    } else {
      Left(
        new CliCommandException(
          result.command,
          result.exitCode,
          result.stdout,
          result.stderr
        )
      )
    }
  }

  /**
   * Simple shell argument escaping for XSI shell.
   * Wraps arguments containing special characters in single quotes.
   */
  private def escapeShellArg(arg: String): String = {
    if arg.matches("[a-zA-Z0-9_/.:-]+") then {
      arg
    } else {
      // Escape single quotes by replacing ' with '\''
      "'" + arg.replace("'", "'\\''") + "'"
    }
  }

}
