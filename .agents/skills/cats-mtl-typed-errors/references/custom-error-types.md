# Custom Error Types Using Cats Effect and MTL

Sources: 
- https://typelevel.org/blog/2025/09/02/custom-error-types.html
- https://typelevel.org/cats-mtl/mtl-classes/raise.html

## Table of Contents
- [Summary](#summary)
- [Core concepts](#core-concepts)
- [Avoid sealed inheritance chains](#avoid-sealed-inheritance-chains)
- [Prefer Cats MTL over EitherT](#prefer-cats-mtl-over-eithert)
- [Capability-based typed errors](#capability-based-typed-errors)
- [Notes on F[_] vs IO](#notes-on-f_-vs-io)
- [Behavior and safety](#behavior-and-safety)
- [Test prompts](#test-prompts)
- [When to use what](#when-to-use-what)

## Summary
- Cats MTL 1.6.0 adds lightweight syntax for user-defined error types without monad transformer stacks.
- Keeps the single Throwable error channel for Cats Effect while allowing scoped typed errors via capabilities.
- Preserves compositional behavior with Cats Effect, Fs2, Http4s, and other libraries.

## Core concepts
- **Monofunctor effects** (`IO[A]`) have a single error channel (`Throwable`).
- **Typed errors** provide explicit, domain-specific errors in the function signature, similar to Java checked exceptions.
- **Cats MTL capabilities** express what a scope can do (raise/handle errors) via implicit evidence.
- **Scoped error handling** with `allow`/`rescue` acts like `try`/`catch` for typed errors.

## Avoid sealed inheritance chains
Avoid having a sealed error type inherit from another sealed error type. Prefer composition so each error ADT stays focused and can be wrapped by the outer domain error.

Wrong:
```scala
sealed trait DomainError

sealed trait ParseError extends DomainError
object ParseError {
  final case class MissingRequiredField(field: String) extends ParseError
}
```

Prefer composition (wrapping):
```scala
sealed trait DomainError
object DomainError {
  final case class Parse(error: ParseError) extends DomainError
}

sealed trait ParseError
object ParseError {
  final case class MissingRequiredField(field: String) extends ParseError
}
```

## Prefer Cats MTL over EitherT
- `IO[Either[E, A]]` forces pervasive `Either`-level plumbing and awkward integration with effect libraries.
- `EitherT` stacks can lead to unintuitive concurrency/resource behavior; avoid in most effectful code.
- Cats MTL provides typed errors without introducing a transformer stack.
- Pure functions returning `Either[E, A]` are acceptable, especially at pure boundaries.

## Capability-based typed errors
Use `Raise[F, E]` for functions that can raise errors and `Handle[F, E]` when you can also recover.
Whether the custom error type extends Throwable is a contextual choice; using Throwable introduces mutability concerns.

Scala 3 (context functions and using):
```scala
import cats.Monad
import cats.effect.IO
import cats.mtl.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*

enum ParseError:
  case UnclosedBracket
  case MissingSemicolon
  case Other(msg: String)

def parse[F[_]](input: String)(using Raise[F, ParseError], Monad[F]): F[Result] =
  if missingBracket then
    ParseError.UnclosedBracket.raise[F, Result]
  else if missingSemicolon then
    ParseError.MissingSemicolon.raise
  else
    result.pure[F]

val program: IO[Unit] = Handle.allow[ParseError]:
  for
    x <- parse[IO](inputX)
    y <- parse(inputY)
    _ <- IO.println(s"successfully parsed $x and $y")
  yield ()
.rescue:
  case ParseError.UnclosedBracket =>
    IO.println("you didn't close your brackets")
  case ParseError.MissingSemicolon =>
    IO.println("you missed your semicolons very much")
  case ParseError.Other(msg) =>
    IO.println(s"error: $msg")
```

Scala 2 (explicit implicits and allowF):
```scala
import cats.Monad
import cats.effect.IO
import cats.mtl.syntax.all._
import cats.mtl.{Handle, Raise}
import cats.syntax.all._

sealed trait ParseError extends Product with Serializable
object ParseError {
  case object UnclosedBracket extends ParseError
  case object MissingSemicolon extends ParseError
  final case class Other(msg: String) extends ParseError
}

def parse[F[_]](input: String)(implicit r: Raise[F, ParseError], m: Monad[F]): F[Result] = {
  if (missingBracket)
    ParseError.UnclosedBracket.raise[F]
  else if (missingSemicolon)
    ParseError.MissingSemicolon.raise[F]
  else
    result.pure[F]
}

val program: IO[Unit] = Handle.allowF[IO, ParseError] { implicit h =>
  for {
    x <- parse[IO](inputX)
    y <- parse[IO](inputY)
    _ <- IO.println(s"successfully parsed $x and $y")
  } yield ()
} rescue {
  case ParseError.UnclosedBracket =>
    IO.println("you didn't close your brackets")
  case ParseError.MissingSemicolon =>
    IO.println("you missed your semicolons very much")
  case ParseError.Other(msg) =>
    IO.println(s"error: $msg")
}
```

## Notes on `F[_]` vs `IO`
- `F[_]` is optional; in many codebases `IO` is used directly.
- `Raise[IO, E]` and `Handle[IO, E]` work fine without abstracting over `F[_]`.
- Using `F[_]` makes the same logic testable in `Either[E, *]` or other monads.

## Behavior and safety
- `allow` creates a lexical scope where `Raise` is available; calling a raising function outside this scope is a compile error.
- `rescue` forces you to handle any raised errors, mirroring try/catch.
- Implementation uses a local traceless Throwable ("submarine error") to transport your domain error within the effect error channel.
- Avoid catching all `Throwable` inside an `allow` scope unless you intentionally want to intercept the submarine error.

## Test prompts
- Replace an `EitherT[IO, E, A]` flow with Cats MTL `Raise/Handle`.
- Refactor a sealed-on-sealed error hierarchy into composition using wrapper case classes.
- Introduce `Raise[F, E]` for typed errors in a parsing function and `rescue` at the boundary.

## When to use what
- Use `Raise[F, E]` for domain errors that should be explicit in signatures.
- Use exceptions only when exposing a specific error type would leak implementation details (for example, a database-specific SQLException) or when you are constrained by an inherited OOP interface that cannot express typed errors.
- Use `Either[E, A]` in pure functions and lift into `F` where needed.
- Avoid `EitherT` in effectful code when Cats MTL can express the capability instead.
