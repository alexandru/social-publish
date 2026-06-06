# Arrow Typed Errors: Context-Parameter API Examples

This reference targets Arrow Core's context-parameter API for Kotlin. The snippets below are represented together in `scripts/verify-examples.kt` and were type-checked with Kotlin `2.3.21`, `-Xcontext-parameters`, and Arrow Core `2.2.2.1`. Kotlin `2.4` is the intended stable context-parameter target; remove the compiler opt-in only when the project compiler supports that.

## Contents
- [Sources and imports](#sources-and-imports)
- [Validated value and Either boundary](#validated-value-and-either-boundary)
- [Bind and translate nested errors](#bind-and-translate-nested-errors)
- [Transform and consume Either](#transform-and-consume-either)
- [Expected exceptions](#expected-exceptions)
- [Validation accumulation](#validation-accumulation)
- [Nullable and Option](#nullable-and-option)
- [Ior for success with notices](#ior-for-success-with-notices)
- [Wrappers with additional states](#wrappers-with-additional-states)

## Sources and imports

Official documentation used for this reference:
- https://arrow-kt.io/learn/typed-errors/
- https://arrow-kt.io/learn/typed-errors/working-with-typed-errors/
- https://arrow-kt.io/learn/typed-errors/validation/
- https://arrow-kt.io/learn/typed-errors/wrappers/
- https://arrow-kt.io/learn/typed-errors/wrappers/nullable-and-option/
- https://arrow-kt.io/learn/typed-errors/wrappers/either-and-ior/
- https://arrow-kt.io/learn/typed-errors/wrappers/outcome-progress/
- https://arrow-kt.io/learn/typed-errors/wrappers/own-error-types/

The official tutorials may show receiver-style functions such as `fun Raise<E>.find(): A`. This skill uses the context-parameter equivalent, `context(_: Raise<E>) fun find(): A`, with imports from `arrow.core.raise.context`.

```kotlin
import arrow.core.Either
import arrow.core.Ior
import arrow.core.NonEmptyList
import arrow.core.Option
import arrow.core.flatMap
import arrow.core.left
import arrow.core.recover
import arrow.core.right
import arrow.core.toOption
import arrow.core.raise.ExperimentalRaiseAccumulateApi
import arrow.core.raise.IorRaise
import arrow.core.raise.Raise as ArrowRaise
import arrow.core.raise.recover as recoverRaised
import arrow.core.raise.context.Raise
import arrow.core.raise.context.accumulate
import arrow.core.raise.context.bind
import arrow.core.raise.context.either
import arrow.core.raise.context.ensure
import arrow.core.raise.context.ensureNotNull
import arrow.core.raise.context.ensureOrAccumulate
import arrow.core.raise.context.ior
import arrow.core.raise.context.mapOrAccumulate
import arrow.core.raise.context.nullable
import arrow.core.raise.context.option
import arrow.core.raise.context.withError
import arrow.core.raise.context.zipOrAccumulate
```

## Validated value and Either boundary

Use `ensure` and `ensureNotNull` for constraints that are logical failures. A context-parameter computation returns its ordinary success value; an `either` boundary turns raised failures into `Either.Left`.

```kotlin
sealed interface ParseError {
    data object Blank : ParseError
    data class NotANumber(val raw: String) : ParseError
    data class NotPositive(val value: Long) : ParseError
}

@JvmInline
value class UserId private constructor(val value: Long) {
    companion object {
        context(_: Raise<ParseError>)
        fun parseInContext(raw: String): UserId {
            ensure(raw.isNotBlank()) { ParseError.Blank }
            val value = ensureNotNull(raw.toLongOrNull()) { ParseError.NotANumber(raw) }
            ensure(value > 0) { ParseError.NotPositive(value) }
            return UserId(value)
        }

        fun parse(raw: String): Either<ParseError, UserId> = either {
            parseInContext(raw)
        }
    }
}
```

Hide constructors for values whose invariants matter. For a Kotlin `data class` with a private constructor, also account for the generated `copy()` API; a plain class or an appropriate copy-visibility policy avoids silently reopening invalid construction.

## Bind and translate nested errors

Call `.bind()` whenever an `Either` value should participate in a contextual computation. Translate a lower-level error into the surrounding error type before binding.

```kotlin
data class User(val id: UserId, val name: String, val email: String?)

sealed interface ServiceError {
    data class InvalidId(val error: ParseError) : ServiceError
    data class MissingUser(val id: UserId) : ServiceError
}

fun findUser(id: UserId): Either<ServiceError, User> =
    if (id.value == 1L) User(id, "Ada", "ada@example.test").right()
    else ServiceError.MissingUser(id).left()

context(_: Raise<ServiceError>)
fun loadUser(rawId: String): User {
    val id = UserId.parse(rawId)
        .mapLeft(ServiceError::InvalidId)
        .bind()
    return findUser(id).bind()
}

fun loadUserEither(rawId: String): Either<ServiceError, User> =
    either { loadUser(rawId) }
```

`mapLeft` is enough when translating an error is a pure mapping. Use `withError` when invoking a contextual computation under a new surrounding error type:

```kotlin
context(_: Raise<ServiceError>)
fun loadUserDirect(rawId: String): User {
    val id = withError({ error: ParseError -> ServiceError.InvalidId(error) }) {
        UserId.parseInContext(rawId)
    }
    return findUser(id).bind()
}
```

Use `recover` instead of `mapLeft` when recovery needs to produce a success value or execute typed-error logic that may raise another failure.

## Transform and consume Either

When already holding `Either`, its operators state intent more directly than reconstructing both branches manually.

```kotlin
fun hasUser(rawId: String): Either<ServiceError, Boolean> =
    loadUserEither(rawId).map { true }

fun displayName(rawId: String): String =
    loadUserEither(rawId).fold(
        { "unknown user" },
        { user -> user.name },
    )

fun loadUserChain(rawId: String): Either<ServiceError, User> =
    UserId.parse(rawId)
        .mapLeft(ServiceError::InvalidId)
        .flatMap(::findUser)

fun loadUserOrFallback(rawId: String): Either<ServiceError, User> =
    loadUserEither(rawId).recover {
        val fallbackId = UserId.parse("1").mapLeft(ServiceError::InvalidId).bind()
        findUser(fallbackId).bind()
    }
```

Use:
- `.map { ... }` when only the success value changes.
- `.mapLeft { ... }` when only the failure value changes.
- `.flatMap { ... }` for a short wrapper-style dependent chain.
- `.fold({ ... }, { ... })` when both branches produce one boundary value.
- `.getOrElse { ... }` when a real fallback value exists.
- `either { ... .bind() ... }` when a longer dependent workflow should read top-to-bottom.

The `recover` example runs another typed computation on failure. Use this only when the fallback is part of the contract; do not conceal a failure merely to avoid handling it.

## Expected exceptions

Convert exceptions into typed failures only when they represent an expected condition in the function's contract. `catchOrThrow<T>` catches the specified exception type and leaves unrelated exceptions exceptional.

```kotlin
sealed interface StoredError {
    data class InvalidCount(val text: String) : StoredError
}

fun parseStoredCount(text: String): Either<StoredError, Int> =
    Either.catchOrThrow<NumberFormatException, Int> { text.toInt() }
        .mapLeft { StoredError.InvalidCount(text) }
```

For database or network code, catch only recoverable expected exceptions, such as a known uniqueness violation; rethrow failures that do not denote a modeled domain result.

## Validation accumulation

An ordinary `either` flow is fail-fast. Use accumulation only for independent validations where reporting several input defects is useful.

`zipOrAccumulate` combines different independent field checks:

```kotlin
sealed interface SignupError {
    data object BlankName : SignupError
    data class UnderAge(val age: Int) : SignupError
}

class Signup private constructor(val name: String, val age: Int) {
    companion object {
        fun create(name: String, age: Int): Either<NonEmptyList<SignupError>, Signup> = either {
            zipOrAccumulate(
                { ensure(name.isNotBlank()) { SignupError.BlankName } },
                { ensure(age >= 18) { SignupError.UnderAge(age) } },
            ) { _, _ -> Signup(name, age) }
        }

        @OptIn(ExperimentalRaiseAccumulateApi::class)
        fun createImperatively(name: String, age: Int): Either<NonEmptyList<SignupError>, Signup> = either {
            accumulate {
                ensureOrAccumulate(name.isNotBlank()) { SignupError.BlankName }
                ensureOrAccumulate(age >= 18) { SignupError.UnderAge(age) }
                Signup(name, age)
            }
        }
    }
}
```

For longer imperative-shaped construction, `accumulate` brings in `ensureOrAccumulate` and related APIs. It is annotated experimental in Arrow `2.2.2.1`, so opt in deliberately; prefer `zipOrAccumulate` for straightforward independent fields.

`mapOrAccumulate` applies one independent validation to collection elements. Preserve indexes when they improve diagnostics:

```kotlin
sealed interface AuthorError {
    data object EmptyName : AuthorError
}

class Author private constructor(val name: String) {
    companion object {
        fun create(name: String): Either<AuthorError, Author> = either {
            ensure(name.isNotBlank()) { AuthorError.EmptyName }
            Author(name)
        }
    }
}

sealed interface BookError {
    data class EmptyAuthor(val index: Int) : BookError
}

fun validateAuthors(names: Iterable<String>): Either<NonEmptyList<BookError>, List<Author>> = either {
    names.withIndex().mapOrAccumulate { indexed ->
        Author.create(indexed.value)
            .mapLeft { BookError.EmptyAuthor(indexed.index) }
            .bind()
    }
}
```

## Nullable and Option

Prefer Kotlin nullable types when absence is the only extra state. Use `Option` when generic or reactive code must distinguish an absent element from a present `null`, or when interoperating with an API that cannot carry nulls.

```kotlin
fun selectEmail(user: User?): String? = nullable {
    user.bind().email.bind()
}

fun selectEmailOption(user: Option<User>): Option<String> = option {
    val email = user.bind().email
    email.toOption().bind()
}

fun possibleEmail(rawId: String): String? =
    loadUserEither(rawId).getOrNull()?.email
```

The last function intentionally discards `ServiceError` at an optional-value boundary. Do not erase a useful error channel accidentally. The receiver-style docs mention `ignoreErrors`; Arrow `2.2.2.1` does not expose that helper through `arrow.core.raise.context`, so contextual code should make this conversion explicit and re-check the API after upgrades.

## Ior for success with notices

Use `Ior` only when a computation may return a usable value together with warnings or notices. Supply how notices combine when more than one is accumulated.

```kotlin
context(notices: IorRaise<List<String>>)
fun normalizeTitle(raw: String): String {
    val title = raw.trim()
    if (title != raw) notices.accumulate(listOf("leading or trailing whitespace removed"))
    return title
}

fun normalizedTitle(raw: String): Ior<List<String>, String> =
    ior(List<String>::plus) { normalizeTitle(raw) }
```

Choose `Either` when warning-plus-success is not meaningful, and choose Kotlin `Result` only when the failure type is intentionally `Throwable` for standard-library or exception-oriented interop.

## Wrappers with additional states

`Option`, `Either`, and `Ior` do not model every useful state machine:
- Quiver `Outcome` distinguishes `Present`, `Failure`, and `Absent`, where absence is not an error.
- Pedestal State `ProgressiveOutcome` represents the latest outcome plus progress such as loading; this is useful for UI or `Flow` state.

Do not force either type into plain `Either` when its additional state is meaningful to callers. Conversely, do not introduce a custom wrapper for ordinary success/failure.

When defining a custom wrapper such as loading/content/failure, follow the official custom-wrapper pattern: make its short-circuiting states a coherent error stratum, implement a builder over `Raise<ThatError>`, and implement its `bind` by intentionally matching its ADT states. This is a place where exhaustive `when` is the API implementation, not boilerplate.

```kotlin
sealed interface Lce<out E, out A> {
    data object Loading : Lce<Nothing, Nothing>
    data class Content<A>(val value: A) : Lce<Nothing, A>
    data class Failure<E>(val error: E) : Lce<E, Nothing>
}

@JvmInline
value class LceScope<E>(private val errors: ArrowRaise<Lce<E, Nothing>>) {
    fun <A> bind(value: Lce<E, A>): A = when (value) {
        is Lce.Content -> value.value
        is Lce.Failure -> errors.raise(value)
        Lce.Loading -> errors.raise(Lce.Loading)
    }
}

context(scope: LceScope<E>)
fun <E, A> Lce<E, A>.bindLce(): A = scope.bind(this)

inline fun <E, A> lce(block: context(LceScope<E>) () -> A): Lce<E, A> =
    recoverRaised({ Lce.Content(block(LceScope(this))) }) { error: Lce<E, Nothing> -> error }

fun titleScreen(ready: Boolean): Lce<String, String> = lce {
    val title = if (ready) Lce.Content("Ready") else Lce.Loading
    title.bindLce()
}
```
