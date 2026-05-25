# Arrow Typed Errors (Arrow Core) - Field Notes

Concise reference for implementing typed errors in Kotlin with Arrow.

Sources:
- https://arrow-kt.io/learn/typed-errors/
- https://arrow-kt.io/learn/typed-errors/working-with-typed-errors/
- https://arrow-kt.io/learn/typed-errors/validation/
- https://arrow-kt.io/learn/typed-errors/wrappers/
- https://arrow-kt.io/learn/typed-errors/wrappers/nullable-and-option/
- https://arrow-kt.io/learn/typed-errors/wrappers/either-and-ior/
- https://arrow-kt.io/learn/typed-errors/wrappers/outcome-progress/
- https://arrow-kt.io/learn/typed-errors/wrappers/own-error-types/

## Core concepts
- Typed errors = logical failures are encoded in the type system (vs exceptions for truly exceptional conditions).
- Two styles:
  - Wrapper types (Either/Option/Ior/Result/nullable) signal errors in the return type.
  - Raise DSL models errors in the computation context via `Raise<E>`.
- Uniform API: `raise`, `ensure`, `ensureNotNull`, `bind`, `recover`, `catch`, `withError`, `ignoreErrors`.

## Avoid sealed inheritance chains
Avoid having a sealed error type inherit from another sealed error type. Prefer composition so each error ADT stays focused and can be wrapped by the outer domain error.

Wrong:
```kotlin
sealed interface DomainError

sealed class ParseError : DomainError {
  data class MissingRequiredField(val field: String) : ParseError()
}
```

Prefer composition (wrapping):
```kotlin
sealed interface DomainError {
  data class Parse(val error: ParseError) : DomainError
}

sealed interface ParseError {
  data class MissingRequiredField(val field: String) : ParseError
}
```

## Prefer Raise DSL for composition
- Use builders: `either {}`, `ior {}`, `result {}`, `option {}`, `nullable {}`.
- Inside builders, call `.bind()` to unwrap typed values; forgetting it is a common bug.
- Convert Raise to wrapper with builders; convert wrapper to Raise with `.bind()`.

## Creating successes and failures
- Either: `value.right()`, `error.left()`.
- Raise: `raise(error)` from within a `Raise<E>` context or builder.
- `ensure(predicate) { error }` and `ensureNotNull(value) { error }` for guard clauses.

## Recovering and transforming
- `recover { error -> ... }` to map one error type to another or provide fallback.
- `withError { mapError }` to transform an error type when binding a different error type.
- `ignoreErrors { ... }` to explicitly drop more informative error types when required (e.g., when binding Either into Option/nullable flows).

## Exceptions vs logical failures
- Wrap foreign/side-effecting code with `catch` to capture expected exceptions and convert to typed errors.
- Use `Either.catchOrThrow<T>` when staying in wrapper style, and rethrow unexpected exceptions.
- Non-fatal exceptions are captured; fatal exceptions propagate.

## Accumulating errors (validation)
- Fail-fast is default for Either/Raise.
- Use accumulation when independent validations should report all problems.
- `mapOrAccumulate` for applying one validation across a collection.
- `zipOrAccumulate` for independent validations producing different results.
- `accumulate { ... }` for imperative-style accumulation; use `ensureOrAccumulate`/`bindOrAccumulate` and `by` when binding results.
- Prefer `EitherNel<E, A>` (alias for `Either<NonEmptyList<E>, A>`) for validation results.

## Validation pattern (parse, don't validate)
- Hide constructors, expose smart constructors returning `Either`.
- Combine field validations with `zipOrAccumulate` or `accumulate`.
- When validating lists, map with index, then `mapOrAccumulate` + `bind`/`bindAll`.

## Wrapper-specific notes
- Option vs nullable: use `Option` to avoid nested nullability in generic code; otherwise nullable is idiomatic.
- `Option.fromNullable` / `.toOption()`; `getOrNull` / `getOrElse` for extraction.
- `Either` and `Ior`: `Ior` supports `Both` for success + warnings; provide error-combining function in `ior` builder if needed.
- Result: similar API but error type is `Throwable`.

## Outcome / progress wrappers (external libs)
- `Outcome` (Quiver) has `Present`, `Failure`, `Absent` (absence is not failure).
- `ProgressiveOutcome` (Pedestal State) models current value + progress (e.g., loading).
- These integrate with Arrow's typed error style by building DSLs on top of `Raise`.

## Custom wrappers
- Build a custom DSL by wrapping `Raise<YourErrorType>`.
- Provide `bind()` for your wrapper and a builder function that returns your wrapper.

## Debugging
- Use `Raise.traced` for tracing `raise`/`bind` call origins in larger call stacks.

## Test prompts
- Convert this exception-driven parser to typed errors with Raise DSL; use error accumulation for validation.
- Refactor a sealed-on-sealed error hierarchy into composition using wrapper data classes.
- Show a boundary function returning `Either` while keeping Raise internally.

## Common snippets
```kotlin
import arrow.core.raise.either
import arrow.core.raise.ensure

data class UserNotFound(val message: String)

fun fetchUser(id: Long): Either<UserNotFound, User> = either {
  ensure(id > 0) { UserNotFound("Invalid id: $id") }
  User(id)
}
```

```kotlin
fun validateUser(name: String, age: Int): EitherNel<UserProblem, User> = either {
  zipOrAccumulate(
    { ensure(name.isNotEmpty()) { UserProblem.EmptyName } },
    { ensure(age >= 0) { UserProblem.NegativeAge(age) } }
  ) { _, _ -> User(name, age) }
}
```
