# Copilot Instructions for Social-Publish Backend (Scala 3)

## Error Handling

### Use Cats-MTL for Error Handling

**DO NOT** use `IO[Either[E, A]]` or `EitherT[IO, E, A]` for error handling. Instead, use Cats-MTL's `Raise` capability.

See: https://typelevel.org/blog/2025/09/02/custom-error-types.html

**Bad:**
```scala
def foo(): IO[Either[MyError, Result]] = ???
def bar(): EitherT[IO, MyError, Result] = ???
```

**Good:**
```scala
import cats.mtl.Raise

def foo[F[_]: Async: [F] =>> Raise[F, MyError]](): F[Result] = ???
```

## Side Effects and IO

### Always Use IO for Side Effects

- Use `IO` for ALL side effects; NO `unsafeRunSync` allowed
- Wrap side-effectful / non-deterministic APIs (e.g., `UUID.randomUUID()`, file I/O, network calls) in `IO`
- **Functions performing side effects MUST return `IO[A]`, not plain `A`**
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

## General Principles

- Functional programming patterns over imperative/OOP style when dealing with data modelling and transformations
- Meaningful names; readable operator chains over cryptic shortcuts
- Prefer beautiful, readable and type-safe code
- No public inner class/trait definitions, unless it's a union-type (sealed trait hierarchy)
- Organize by component / let encapsulation drive the organization
