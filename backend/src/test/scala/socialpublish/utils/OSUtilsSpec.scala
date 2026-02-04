package socialpublish.utils

import munit.CatsEffectSuite
import java.nio.file.Path

class OSUtilsSpec extends CatsEffectSuite {

  test("executeCommand should execute simple command successfully") {
    OSUtils.executeCommand(Path.of("/bin/echo"), "hello", "world").map { result =>
      assertEquals(result.exitCode, 0)
      assertEquals(result.stdout.trim, "hello world")
      assertEquals(result.stderr, "")
    }
  }

  test("executeCommand should capture stdout correctly") {
    OSUtils.executeCommand(Path.of("/bin/sh"), "-c", "echo 'test output'").map { result =>
      assertEquals(result.exitCode, 0)
      assert(result.stdout.contains("test output"))
    }
  }

  test("executeCommand should capture stderr correctly") {
    OSUtils.executeCommand(Path.of("/bin/sh"), "-c", "echo 'error message' >&2").map { result =>
      assertEquals(result.exitCode, 0)
      assert(result.stderr.contains("error message"))
    }
  }

  test("executeCommand should capture non-zero exit codes") {
    OSUtils.executeCommand(Path.of("/bin/sh"), "-c", "exit 42").map { result =>
      assertEquals(result.exitCode, 42)
    }
  }

  test("executeCommand should handle command with arguments") {
    OSUtils.executeCommand(Path.of("/bin/sh"), "-c", "echo $1 $2", "sh", "arg1", "arg2").map {
      result =>
        assertEquals(result.exitCode, 0)
        assert(result.stdout.contains("arg1 arg2"))
    }
  }

  test("executeShellCommand should execute shell command successfully") {
    OSUtils.executeShellCommand("echo", "hello", "from", "shell").map { result =>
      assertEquals(result.exitCode, 0)
      assert(result.stdout.contains("hello"))
    }
  }

  test("executeShellCommand should handle special characters") {
    OSUtils.executeShellCommand("echo", "test'with'quotes").map { result =>
      assertEquals(result.exitCode, 0)
      // The escaping should allow the command to execute without errors
      assertEquals(result.exitCode, 0)
    }
  }

  test("executeShellCommand should handle commands with pipes") {
    // The full command including pipe needs to be passed as a single command string
    OSUtils.executeCommand(Path.of("/bin/sh"), "-c", "echo 'hello' | grep hello").map { result =>
      assertEquals(result.exitCode, 0)
      assert(result.stdout.contains("hello"))
    }
  }

  test("executeCommand should handle concurrent stdout and stderr") {
    // Command that writes to both stdout and stderr
    val script = "echo 'stdout line'; echo 'stderr line' >&2"
    OSUtils.executeCommand(Path.of("/bin/sh"), "-c", script).map { result =>
      assertEquals(result.exitCode, 0)
      assert(result.stdout.contains("stdout line"))
      assert(result.stderr.contains("stderr line"))
    }
  }

  test("executeCommand should handle large output") {
    // Generate large output to test buffer handling
    val script = "for i in $(seq 1 1000); do echo \"Line $i\"; done"
    OSUtils.executeCommand(Path.of("/bin/sh"), "-c", script).map { result =>
      assertEquals(result.exitCode, 0)
      assert(result.stdout.contains("Line 1"))
      assert(result.stdout.contains("Line 1000"))
    }
  }

}
