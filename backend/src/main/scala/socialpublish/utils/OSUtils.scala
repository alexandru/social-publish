package socialpublish.utils

import cats.effect.IO
import org.apache.commons.text.StringEscapeUtils
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path

final case class CommandResult(
  exitCode: Int,
  stdout: String,
  stderr: String
)

object OSUtils {
  def executeCommand(executable: Path, args: String*): IO[CommandResult] =
    IO.blocking {
      val commandArgs = executable.toAbsolutePath.toString +: args
      Runtime.getRuntime.exec(commandArgs.toArray)
    }
      // A `bracket` works like `try-with-resources` or `try-finally`
      .bracket { proc =>
        // These aren't "interruptible", what actually interrupts them
        // is proc.destroy(); and due to how they are used, it's better
        // to not declare them as interruptible, as to not mislead:
        val collectStdout = IO.blocking {
          new String(proc.getInputStream.readAllBytes(), UTF_8)
        }
        val collectStderr = IO.blocking {
          new String(proc.getErrorStream.readAllBytes(), UTF_8)
        }
        // This is actually cancellable via thread interruption
        val awaitReturnCode = IO.interruptible {
          proc.waitFor()
        }
        for {
          // Starts jobs asynchronously
          stdoutFiber <- collectStdout.start
          stderrFiber <- collectStderr.start
          // Waits for process to complete
          code <- awaitReturnCode
          // Reads output
          stdout <- stdoutFiber.joinWithNever
          stderr <- stderrFiber.joinWithNever
        } yield {
          CommandResult(code, stdout, stderr)
        }
      } { proc =>
        IO.blocking {
          proc.destroy()
        }
      }

  def executeShellCommand(command: String, args: String*): IO[CommandResult] =
    executeCommand(
      Path.of("/bin/sh"),
      "-c",
      (command +: args).map(StringEscapeUtils.escapeXSI).mkString(" ")
    )
}
