# Kotlin Context Parameters - Reference

Guidance for Kotlin's `context(...)` parameters.

Sources:

- <https://kotlinlang.org/docs/context-parameters.html>
- <https://raw.githubusercontent.com/Kotlin/KEEP/refs/heads/main/proposals/KEEP-0367-context-parameters.md>

## Table of Contents

- [Version notes](#version-notes)
- [Core syntax and call sites](#core-syntax-and-call-sites)
- [Resolution rules](#resolution-rules)
- [Choosing names vs underscore](#choosing-names-vs-underscore)
- [Context function types](#context-function-types)
- [Context properties](#context-properties)
- [Common patterns](#common-patterns)
- [Migration from context receivers](#migration-from-context-receivers)
- [Current restrictions](#current-restrictions)
- [Checklist](#checklist)
- [Test prompts](#test-prompts)

## Version notes

Context parameters:

- Available in preview starting with Kotlin 2.2.0
- Support needs the `-Xcontext-receivers` compiler option; and `-Xcontext-receivers` must be disabled.
- Feature is becoming stable in Kotlin 2.4.0, so additional compiler options may not be needed.

## Core syntax and call sites

Declare context parameters before the function or property. The parameter is accessible by name in the body like a value parameter, but callers do not pass it in the ordinary argument list.

```kotlin
interface Logger {
  fun log(message: String)
}

class ConsoleLogger : Logger {
  override fun log(message: String) = println(message)
}

context(logger: Logger)
fun saveUser(name: String) {
  logger.log("Saving $name")
}

fun main() {
  context(ConsoleLogger()) {
    saveUser("Ada")
  }
}
```

An implicit receiver can also satisfy a context parameter:

```kotlin
fun main() {
  with(ConsoleLogger()) {
    saveUser("Ada")
  }
}
```

## Resolution rules

- Resolution happens at the call site by type.
- Parameter names improve readability inside declarations but do not select a context at the call site.
- The compiler searches in-scope context parameters and implicit receivers.
- If more than one compatible value exists at the same scope level, the call is ambiguous.
- A more nested compatible context value can resolve a call without ambiguity.

Avoid APIs that require users to keep several same-typed context values in scope. Introduce wrapper types when two dependencies have the same underlying type but distinct meanings. This is the "newtype" idiom used in Haskell, Scala, Rust, etc.

```kotlin
@JvmInline value class AuditLogger(val logger: Logger)
@JvmInline value class AppLogger(val logger: Logger)
```

## Choosing names vs underscore

Use a named context parameter when the implementation needs to call the dependency directly:

```kotlin
context(users: UserService)
fun displayName(id: Int): String = users.findName(id)
```

Use `_` when the value is only evidence that an operation is available, or when bridge functions hide the underlying scope object:

```kotlin
interface Transaction {
  fun rows(sql: String): List<String>
}

context(tx: Transaction)
fun rows(sql: String): List<String> = tx.rows(sql)

context(_: Transaction)
fun activeUsers(): List<String> = rows("select * from users where active")
```

When a context parameter is unnamed, access it by type with `contextOf<T>()` if needed:

```kotlin
context(_: Logger)
fun debug(message: String) {
  contextOf<Logger>().log("DEBUG: $message")
}
```

## Context function types

Use `context(Type) () -> A` for callbacks that run with a contextual value available.

```kotlin
fun <A> withLogger(logger: Logger, block: context(Logger) () -> A): A =
  context(logger) { block() }

fun example() {
  withLogger(ConsoleLogger()) {
    saveUser("Grace")
    contextOf<Logger>().log("done")
  }
}
```

Names are not part of context function types; write `context(Logger) () -> A`, not `context(logger: Logger) () -> A`.

## Context properties

Properties can require context parameters, including extension properties:

```kotlin
data class User(val id: Int)

interface UserService {
  fun displayName(id: Int): String
}

context(users: UserService)
val User.displayName: String
  get() = users.displayName(id)
```

Restrictions for context properties:

- no backing field;
- no initializer;
- no delegation;
- context parameters belong to the property as a whole, not only to the getter or setter.

## Common patterns

### Scoped services / dependency injection

Context parameters work well for stable services that are shared across a call tree, such as repositories, loggers, transactions, request sessions, or serializers.

```kotlin
context(logger: Logger, users: UserService)
fun register(name: String): User {
  logger.log("registering $name")
  return users.create(name)
}
```

Keep object construction explicit when many dependencies are involved:

```kotlin
val logger = ConsoleLogger()
val users = InMemoryUserService()

context(logger, users) {
  register("Katherine")
}
```

### Scope objects and bridge functions

For scope-style APIs, keep the scope type focused and expose top-level bridge functions. This avoids turning context parameters into broad implicit receivers.

```kotlin
interface Raise<in E> {
  fun raise(error: E): Nothing
}

context(r: Raise<E>)
fun <E> raise(error: E): Nothing = r.raise(error)

context(_: Raise<String>)
fun parsePositive(raw: String): Int {
  val value = raw.toIntOrNull() ?: raise("not an int")
  if (value <= 0) raise("not positive")
  return value
}
```

### Typeclass-like evidence

Use context parameters to require externally supplied behavior for a type. Keep the required interface small.

```kotlin
interface Encoder<T> {
  fun encode(value: T): String
}

context(encoder: Encoder<T>)
fun <T> T.encode(): String = encoder.encode(this)
```

Avoid creating parallel overload families that differ only by contextual evidence; overload resolution can become surprising.

## Migration from context receivers

Context parameters replace the older experimental context receivers.

Old style:

```kotlin
// Context receivers: old syntax, not context-parameter syntax.
context(Logger)
fun saveUser(name: String) {
  log("Saving $name")
}
```

New style:

```kotlin
context(logger: Logger)
fun saveUser(name: String) {
  logger.log("Saving $name")
}
```

Migration notes:

- Replace anonymous context receivers with named parameters, or `_` plus bridge functions.
- Replace `this@Type` patterns with the parameter name or `contextOf<Type>()`.
- Move operations out of scope interfaces when possible and expose top-level contextual functions.
- Constructors and classes cannot declare contexts; use explicit constructor parameters or contextual companion factories.

Contextual companion factory workaround:

```kotlin
class UserService private constructor(
  private val logger: Logger,
  private val connection: DbConnection,
) {
  companion object {
    context(logger: Logger, connection: DbConnection)
    operator fun invoke(): UserService = UserService(logger, connection)
  }
}
```

## Current restrictions

From the official docs and KEEP:

- Constructors cannot declare context parameters.
- Classes cannot declare class-level contexts in the current design.
- Empty context parameter lists are invalid.
- Context parameter names must not collide with other context or value parameters, except multiple `_` names.
- Context properties cannot have backing fields, initializers, or delegates.
- Context function type parameters list only types, not names.

## Checklist

- The project uses a Kotlin version/configuration that supports context parameters.
- Context parameters are named only when the implementation needs direct access.
- Same-typed dependencies are wrapped or kept out of the same scope level.
- Contextual properties avoid initializers, backing fields, and delegation.
- Migration examples use parameter names, bridge functions, or `contextOf<T>()` instead of `this@Type`.

## Test prompts

- "Refactor this Kotlin context receiver API to context parameters and explain the migration."
- "Design a transaction-scoped Kotlin DSL using context parameters and bridge functions."
- "Add context-parameter based logging to this service layer without passing Logger explicitly everywhere."
- "Review this Kotlin code for context parameter ambiguity and restrictions."
- "For these class methods make the current user a context parameter via UserSession"
