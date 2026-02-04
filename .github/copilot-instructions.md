# Copilot Instructions for Social-Publish Backend (Scala 3)

## Agent Skills

This project uses agent skills for focused best practices. See AGENTS.md for the full list of skills and guidelines.

**Key skills to apply:**
- `cats-effect-io` — Proper use of `IO` for side effects, blocking operations, and concurrency
- `cats-effect-resource` — Resource lifecycle management with `Resource` and proper cleanup
- `cats-mtl-typed-errors` — Typed domain errors using Cats MTL `Raise/Handle`

## Error Handling

### Use Cats-MTL for Error Handling (Preferred)

Use Cats MTL's `Raise[F, E]` and `Handle` for typed domain errors. This avoids `IO[Either[E, A]]` and `EitherT[IO, E, A]` patterns.

See: https://typelevel.org/blog/2025/09/02/custom-error-types.html

**Bad:**
```scala
def foo(): IO[Either[MyError, Result]] = ???
def bar(): EitherT[IO, MyError, Result] = ???
```

**Good (Scala 3):**
```scala
import cats.Monad
import cats.mtl.Raise
import cats.mtl.syntax.all.*

enum MyError {
  case InvalidInput(msg: String)
  case NotFound(id: String)
}

def foo[F[_]: Monad](input: String)(using Raise[F, MyError]): F[Result] = {
  if input.isEmpty then
    MyError.InvalidInput("input cannot be empty").raise[F, Result]
  else
    result.pure[F]
}

// At the boundary, use Handle.allow/rescue
val program: IO[Unit] = Handle.allow[MyError]:
  for
    x <- foo[IO]("valid")
    _ <- IO.println(s"result: $x")
  yield ()
.rescue:
  case MyError.InvalidInput(msg) => IO.println(s"Invalid: $msg")
  case MyError.NotFound(id) => IO.println(s"Not found: $id")
```

**Transition Note:** Existing code may use `IO[Either[E, A]]`. This is acceptable during transition but new code should use Cats MTL. Refactor to Cats MTL when touching error-handling code.

## Side Effects and IO

### Always Use IO for Side Effects

- Use `IO` for ALL side effects; NO `unsafeRunSync` allowed
- Wrap side-effectful / non-deterministic APIs (e.g., `UUID.randomUUID()`, file I/O, network calls) in `IO`
- **Functions performing side effects MUST return `IO[A]` (or `F[A]`), not plain `A`**
- If a function does I/O operations (file system, network, random values, etc.), mark it as returning `IO`

**Bad:**
```scala
private def validateFiles(source: File, dest: File): Either[MyError, Unit] = {
  if !source.exists() then { // Side effect: file system check
    Left(...)
  } else {
    Right(())
  }
}
```

**Good:**
```scala
private def validateFiles(source: File, dest: File): IO[Either[MyError, Unit]] = IO {
  if !source.exists() then {
    Left(...)
  } else {
    Right(())
  }
}
```

**Better (with Cats MTL):**
```scala
private def validateFiles[F[_]: Monad](
  source: File, 
  dest: File
)(using Raise[F, MyError]): F[Unit] = {
  IO.blocking {
    if !source.exists() then Left(MyError.NotFound(source.getPath))
    else Right(())
  }.liftF[F].flatMap {
    case Left(err) => err.raise[F, Unit]
    case Right(_) => Monad[F].unit
  }
}
```

## Blocking I/O

### Use Proper Blocking Combinators

- Wrap Java blocking calls in `IO.blocking` or `IO.interruptible`
- Use `IO.interruptible` for operations that can throw `InterruptedException`
- Use `IO.blocking` for cleanup/disposal operations

**Examples:**
```scala
// Interruptible blocking (can be cancelled)
IO.interruptible {
  process.waitFor()
}

// Non-interruptible blocking (cleanup/disposal)
IO.blocking {
  stream.close()
}
```

## Exception Handling

### Use NonFatal for Exception Handling

Always use `scala.util.control.NonFatal` when catching exceptions, never catch all exceptions blindly.

**Bad:**
```scala
try {
  riskyOperation()
} catch {
  case _: Exception => // Catches fatal errors too!
    handleError()
}
```

**Good:**
```scala
import scala.util.control.NonFatal

try {
  riskyOperation()
} catch {
  case NonFatal(e) => // Only catches non-fatal exceptions
    handleError(e)
}
```

## Code Style

### Avoid Symbolic Operators

Prefer readable chains (`flatMap`, for-comprehensions) over symbolic operators (`*>`, `>>`, `<*`, etc.)

**Bad:**
```scala
logger.warn("message") *> 
  IO.blocking(dest.delete()) *> {
    doSomething()
  }
```

**Good:**
```scala
logger.warn("message").flatMap { _ =>
  IO.blocking(dest.delete()).flatMap { _ =>
    doSomething()
  }
}

// Or use for-comprehension:
for {
  _ <- logger.warn("message")
  _ <- IO.blocking(dest.delete())
  result <- doSomething()
} yield result
```

### Braces Required

- Braces required; NO braceless syntax (enforced via `scalafmt.conf`)
- Always use braces for blocks, even single-line ones

## Resource Management

### Use Resource for Lifecycle Management

Use `cats.effect.Resource` for managing lifecycle of resources (files, connections, pools, etc.)

**Good:**
```scala
import cats.effect.Resource

def makeClient: Resource[IO, HttpClient] =
  Resource.make(
    IO.blocking(HttpClient.newHttpClient())
  )(client =>
    IO.blocking(client.close())
  )

val program: IO[Response] =
  makeClient.use { client =>
    client.send(request)
  }
```

## Error Design Patterns

### Avoid Sealed-on-Sealed Inheritance

Don't create sealed error types that extend other sealed error types. Use composition instead.

**Bad:**
```scala
sealed trait DomainError
sealed trait ParseError extends DomainError  // Don't do this!
```

**Good:**
```scala
enum DomainError {
  case Parse(error: ParseError)
  case Validation(error: ValidationError)
}

enum ParseError {
  case MissingField(name: String)
  case InvalidFormat(msg: String)
}
```

## General Principles

- Functional programming patterns over imperative/OOP style when dealing with data modelling and transformations
- Meaningful names; readable operator chains over cryptic shortcuts
- Prefer beautiful, readable and type-safe code
- No public inner class/trait definitions, unless it's a union-type (sealed trait hierarchy)
- Organize by component / let encapsulation drive the organization
- Consult agent skills (in AGENTS.md) for detailed patterns and recipes
