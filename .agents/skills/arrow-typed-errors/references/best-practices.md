# Arrow Typed Errors: Best Practices and Simplifications

Use this guide when choosing a typed-error design or simplifying code that already returns `Either`. It combines the official Arrow guidance with practical refactoring cases; the objective is concise intent, not elimination of Kotlin `when`. Complete type-checked API examples are in `typed-errors.md` and `scripts/verify-examples.kt`; snippets here use application-shaped placeholder types where the refactoring intent matters.

## Contents

- [Choose the failure channel deliberately](#choose-the-failure-channel-deliberately)
- [Prefer contextual Raise for workflows](#prefer-contextual-raise-for-workflows)
- [Replace boilerplate Either branching](#replace-boilerplate-either-branching)
- [Use when where it expresses the domain](#use-when-where-it-expresses-the-domain)
- [Translate and recover at boundaries](#translate-and-recover-at-boundaries)
- [Validate independent inputs together](#validate-independent-inputs-together)
- [Keep ordinary control flow ordinary](#keep-ordinary-control-flow-ordinary)
- [Select the smallest wrapper](#select-the-smallest-wrapper)
- [Keep custom wrappers interoperable](#keep-custom-wrappers-interoperable)
- [Review checklist](#review-checklist)
- [Sources](#sources)

## Choose the failure channel deliberately

Model failures that callers are expected to handle in types. Leave infrastructure faults, cancellation, and unrecoverable failures exceptional unless the contract specifically turns one into a domain result.

```kotlin
sealed interface SaveError {
    data object DuplicateUsername : SaveError
}

fun save(username: String): Either<SaveError, Long> =
    Either.catchOrThrow<SQLException, Long> { insert(username) }
        .mapLeft { error ->
            if (error.isUniqueViolation()) SaveError.DuplicateUsername else throw error
        }
```

This converts the known duplicate case; it does not flatten every database failure into a misleading domain value.

When two catch branches have identical handling and one exception type is already covered by another, keep only the verified common catch. This is ordinary exception simplification and is compatible with typed conversion; confirm the inheritance relationship in the dependency version before removing a branch.

## Prefer contextual Raise for workflows

For dependent multi-step logic, an internal context-parameter function avoids nested wrapper plumbing while preserving a typed boundary:

```kotlin
context(_: Raise<ServiceError>)
fun authenticate(rawId: String): Session {
    val id = UserId.parse(rawId).mapLeft(ServiceError::InvalidId).bind()
    val user = findUser(id).bind()
    return createSession(user).bind()
}

fun authenticateEither(rawId: String): Either<ServiceError, Session> =
    either { authenticate(rawId) }
```

Inside `either {}` or a `Raise<E>` context, do not inspect an `Either` just to propagate it. Translate as needed and call `.bind()`. Consider enabling Arrow's Detekt rule for unused bindable values so a bare `Either` statement in a builder is caught.

## Replace boilerplate Either branching

Use the operator matching the one thing being changed:

| Intent                                          | API                                        |
| ----------------------------------------------- | ------------------------------------------ |
| Change successful value only                    | `.map { ... }`                             |
| Change error value only                         | `.mapLeft { ... }`                         |
| Continue with another `Either`                  | `.flatMap { ... }` or contextual `.bind()` |
| Collapse error/success into one value           | `.fold({ ... }, { ... })`                  |
| Return an actual fallback success value         | `.getOrElse { ... }`                       |
| Recovery may raise or perform typed-error logic | `.recover { ... }`                         |

Transforming only success:

```kotlin
fun hasToken(id: UserId): Either<StorageError, Boolean> =
    restoreToken(id).map { token -> token != null }
```

Do not spell this as a `when` that recreates the unchanged `Left`.

Translating an error while binding:

```kotlin
context(_: Raise<ApiError>)
fun loadDocument(key: String): Document =
    parseDocument(readPayload(key).bind())
        .mapLeft { error -> ApiError.InvalidDocument(key, error) }
        .bind()
```

Avoid expanding the same operation into a forwarding `when`:

```kotlin
context(_: Raise<ApiError>)
fun loadDocument(key: String): Document =
    when (val parsed = parseDocument(readPayload(key).bind())) {
        is Either.Left -> raise(ApiError.InvalidDocument(key, parsed.value))
        is Either.Right -> parsed.value
    }
```

The `mapLeft(...).bind()` version says that only the error type changes and that success continues; the `when` version repeats those mechanics.

Consuming an `Either` at a boundary:

```kotlin
fun hasValidAccessToken(payload: String): Boolean =
    parseDocument(payload).fold(
        { false },
        { document -> document.accessToken != null },
    )
```

## Use when where it expresses the domain

`when` is not an anti-pattern. It is appropriate for constructing a typed result from genuine mutually exclusive rules:

```kotlin
fun adultAge(value: Int): Either<AgeError, Age> = when {
    value < 0 -> AgeError.Negative.left()
    value < 18 -> AgeError.Minor.left()
    else -> Age(value).right()
}
```

It is also appropriate when rendering or implementing state-rich ADTs such as `Ior`, `Outcome`, progress states, or a custom loading/content/failure wrapper. Replace `when` only when the branches merely reproduce generic `Either` mechanics and an operator makes the intended change clearer.

## Translate and recover at boundaries

Keep lower-level error types focused, and translate them as they cross a layer:

```kotlin
context(_: Raise<CheckoutError>)
fun checkout(rawCard: String): Receipt {
    val card = withError(CheckoutError::InvalidCard) {
        parseCard(rawCard)
    }
    return charge(card).bind()
}
```

Use `.mapLeft` for a pure value mapping on an existing wrapper. Use `withError` when running contextual code. Use `.recover` when an error starts a fallback or may produce a different typed failure:

```kotlin
fun profile(id: UserId): Either<ProfileError, Profile> =
    cachedProfile(id).recover { _: CacheMiss ->
        remoteProfile(id).bind()
    }
```

## Validate independent inputs together

Default `Either`/`Raise` behavior fails fast; that is correct for dependent steps. For independent input rules, report all useful defects:

```kotlin
fun registration(name: String, age: Int): Either<NonEmptyList<InputError>, Registration> = either {
    zipOrAccumulate(
        { ensure(name.isNotBlank()) { InputError.BlankName } },
        { ensure(age >= 18) { InputError.UnderAge(age) } },
    ) { _, _ -> Registration(name, age) }
}
```

For homogeneous collections, use `mapOrAccumulate`; retain indexes when a caller needs to identify invalid entries. Do not accumulate operations with dependencies or side effects merely to return more errors.

When validation is more naturally imperative, Arrow also provides the `accumulate` scope with `ensureOrAccumulate` and `bindOrAccumulate`. In Arrow `2.2.2.1` that API is experimental; opt in only when it makes complex validation materially clearer.

## Keep ordinary control flow ordinary

Typed errors do not require turning ordinary null branching into chains. If one branch supplies a default and the other contains substantive typed-error work, use `if` so both paths remain visible:

```kotlin
context(_: Raise<StorageError>)
fun storedDocument(row: StoredRow?): Document =
    if (row == null) {
        Document.empty()
    } else {
        parseDocument(row.payload)
            .mapLeft(StorageError::InvalidPayload)
            .bind()
    }
```

Likewise, do not re-run side effects to answer a question about an already loaded value. Parse or inspect the value already in hand:

```kotlin
fun hasValidAccessToken(payload: String): Boolean =
    parseDocument(payload).fold(
        { false },
        { document -> document.accessToken != null },
    )
```

This can replace an API that performs another database query solely to return a derived Boolean.

## Select the smallest wrapper

| Need                                                 | Preferred representation             |
| ---------------------------------------------------- | ------------------------------------ |
| Absence without diagnostic detail                    | `A?`                                 |
| Absence in nested-null/generic/non-null carrier code | `Option<A>`                          |
| Disjoint logical failure or success                  | `Either<E, A>`                       |
| Usable success plus warnings/notices                 | `Ior<E, A>`                          |
| Failure deliberately restricted to thrown values     | `Result<A>`                          |
| Absence distinct from failure                        | An `Outcome`-style wrapper           |
| Latest value plus loading/progress                   | A `ProgressiveOutcome`-style wrapper |

Choosing a richer wrapper than required makes every consumer handle states that cannot meaningfully occur.

## Keep custom wrappers interoperable

Create a wrapper only when its extra states are real domain or UI states. If a loading/content/failure or dialog-result ADT needs a Raise DSL, define a dedicated error stratum for non-success cases and build over `Raise<ErrorState>`. This preserves interoperation with `Either<ErrorState, A>` and accumulation APIs.

Its `bind` implementation should exhaustively match the wrapper's states; that is essential logic, unlike matching `Either` merely to forward `Left` and unwrap `Right`.

## Review checklist

- The failure type describes expected, actionable failures rather than every exception.
- New workflow code uses `arrow.core.raise.context` and context parameters.
- Each nested `Either` in a contextual flow is bound, with errors translated at its boundary.
- `.map`, `.mapLeft`, `.fold`, `.getOrElse`, `.recover`, or `.bind()` replaces only boilerplate matching.
- Remaining `when` branches represent real domain or wrapper-state decisions.
- Validation accumulates only independent defects.
- Optional results do not silently erase errors that callers need.
- Wrapper choice represents no more states than the contract requires.
- New snippets are type-checked against the project's Arrow and Kotlin versions.

## Sources

- https://arrow-kt.io/learn/typed-errors/working-with-typed-errors/
- https://arrow-kt.io/learn/typed-errors/validation/
- https://arrow-kt.io/learn/typed-errors/wrappers/nullable-and-option/
- https://arrow-kt.io/learn/typed-errors/wrappers/either-and-ior/
- https://arrow-kt.io/learn/typed-errors/wrappers/outcome-progress/
- https://arrow-kt.io/learn/typed-errors/wrappers/own-error-types/
