package socialpublish.integrations.imagemagick

import cats.effect.IO
import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

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

  /** Executes a command using ProcessBuilder. This runs the command directly without going through
    * /bin/sh. Reads stdout and stderr concurrently to prevent deadlocks.
    */
  def executeCommand(executable: String, args: String*): IO[CommandResult] = {
    IO.blocking {
      val cmdList = (executable +: args).toList.asJava
      val processBuilder = new ProcessBuilder(cmdList)
      val process = processBuilder.start()

      // Read stdout and stderr concurrently to prevent deadlocks
      val stdoutFuture = IO.blocking {
        new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      }
      val stderrFuture = IO.blocking {
        new String(process.getErrorStream.readAllBytes(), StandardCharsets.UTF_8)
      }

      // Wait for both streams concurrently
      val combined = (stdoutFuture, stderrFuture).parTupled

      (combined, IO.blocking(process.waitFor())).parTupled.map {
        case ((stdout, stderr), exitCode) =>
          CommandResult(
            command = (executable +: args).mkString(" "),
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr
          )
      }
    }.flatten
  }

  /** Executes a shell command through /bin/sh. Arguments are escaped and joined with spaces.
    */
  def executeShellCommand(command: String, args: String*): IO[CommandResult] = {
    val escapedArgs = args.map(escapeShellArg)
    val shellCommand = command + " " + escapedArgs.mkString(" ")
    executeCommand("/bin/sh", "-c", shellCommand)
  }

  /** Checks if the result has a successful exit code (0). Returns Right(stdout) on success,
    * Left(exception) on failure.
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

  /** Simple shell argument escaping for XSI shell. Wraps arguments containing special characters in
    * single quotes.
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
