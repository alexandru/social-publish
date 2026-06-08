# Arrow Resource (Kotlin) - Practical Guide

Use this reference when designing or reviewing Kotlin APIs that own files, streams,
clients, pools, sockets, workers, database connections, multipart parts, temporary
directories, or any other disposable handle.

Sources:

- <https://arrow-kt.io/learn/coroutines/resource-safety/>
- <https://apidocs.arrow-kt.io/arrow-fx-coroutines/arrow.fx.coroutines/-resource/index.html>
- <https://apidocs.arrow-kt.io/arrow-fx-coroutines/arrow.fx.coroutines.resource.context/index.html>
- <https://apidocs.arrow-kt.io/arrow-autoclose/arrow/auto-close-scope.html>

Typechecked representative check: `scripts/verify-examples.kt`.

## Table of Contents

- [The Rule](#the-rule)
- [Core Model](#core-model)
- [API Surface](#api-surface)
- [Context-Parameter Style](#context-parameter-style)
- [Class Design](#class-design)
- [No Leaking](#no-leaking)
- [AutoCloseable Without Suspend](#autocloseable-without-suspend)
- [Typed Errors And ExitCase](#typed-errors-and-exitcase)
- [Review Checklist](#review-checklist)
- [Representative Prompts](#representative-prompts)

## The Rule

Resource management is an ownership discipline:

- Every value that needs disposal must be acquired through `Resource`, `ResourceScope.install`, `autoCloseable`, `closeable`, or a higher-level builder that uses them.
- The raw resource reference must not outlive the scope that acquired it.
- Do not return raw resource handles from `resourceScope { ... }`, `resource { ... }`, or `resource.use { ... }`.
- A class that owns resources must not allocate them in an ordinary constructor or initializer. Put acquisition in a companion builder returning `Resource<A>` or requiring `context(ResourceScope)`.
- If the code cannot be suspend/resource-based, use `arrow-autoclose` and `AutoCloseable`, with the same rule: no resource references escape their close scope.

This is functional-programming resource discipline: acquisition and release are explicit effects that compose. Avoid hidden side-effectful constructors, global mutable handles, or "open now, remember to close later" APIs.

## Core Model

- `typealias Resource<A> = suspend ResourceScope.() -> A`
- A `Resource<A>` is a recipe, not an acquired value.
- The lifecycle is acquire -> use -> release.
- Release finalizers run when `resourceScope` or `Resource.use` exits.
- Finalizers run in reverse acquisition order.
- `ExitCase` tells the finalizer why it is running: completed, failed, or cancelled.
- Arrow runs acquire and release in a non-cancellable section. If acquisition fails before the value is acquired, that resource's release does not run; already acquired resources are still released.

Use `Resource` when cleanup is suspendable, when cancellation/structured concurrency matters, when resources compose, or when multiplatform support matters.

## API Surface

For context-parameter-ready Kotlin, import the context package:

```kotlin
import arrow.fx.coroutines.resource.context.Resource
import arrow.fx.coroutines.resource.context.ResourceScope
import arrow.fx.coroutines.resource.context.bind
import arrow.fx.coroutines.resource.context.install
import arrow.fx.coroutines.resource.context.resource
import arrow.fx.coroutines.resource.context.resourceScope
```

The context package provides `Resource`/`ResourceScope` aliases and context-shaped
functions:

- `context(ResourceScope) suspend fun <A> Resource<A>.bind(): A`
- `context(ResourceScope) suspend fun <A> install(acquire, release): A`
- `context(ResourceScope) suspend fun <A : AutoCloseable> autoCloseable(...): A`
- `fun <A> resource(block: context(ResourceScope) suspend () -> A): Resource<A>`
- `suspend fun <A> resourceScope(action: context(ResourceScope) suspend () -> A): A`

Use direct `resource(acquire, release)` for one simple resource, and `resource { ... }`
when the resource is built from other resources:

```kotlin
import arrow.fx.coroutines.resource.context.Resource
import arrow.fx.coroutines.resource.context.resource

class UserProcessor {
  fun start() {}
  fun shutdown() {}
}

fun userProcessorResource(): Resource<UserProcessor> =
  resource(
    acquire = { UserProcessor().also { it.start() } },
    release = { processor, _ -> processor.shutdown() },
  )
```

JVM `AutoCloseable` values can be wrapped directly:

```kotlin
import arrow.fx.coroutines.resource.context.Resource
import arrow.fx.coroutines.resource.context.autoCloseable
import arrow.fx.coroutines.resource.context.resource
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path

fun linesResource(path: Path): Resource<BufferedReader> =
  resource {
    autoCloseable { Files.newBufferedReader(path) }
  }
```

Inside `resourceScope` or `resource {}`, a `Resource<A>` is acquired with `.bind()`:

```kotlin
import arrow.fx.coroutines.resource.context.Resource
import arrow.fx.coroutines.resource.context.bind
import arrow.fx.coroutines.resource.context.resource
import arrow.fx.coroutines.resource.context.resourceScope

class Service private constructor(
  private val processor: UserProcessor,
) {
  fun run(): String = processor.toString()

  companion object {
    fun resource(): Resource<Service> = resource {
      Service(userProcessorResource().bind())
    }
  }
}

suspend fun runService(): String =
  resourceScope {
    Service.resource().bind().run()
  }
```

## Context-Parameter Style

Prefer context parameters for reusable scoped constructors. They make the required
capability visible without requiring callers to thread a `ResourceScope` manually.

```kotlin
import arrow.fx.coroutines.resource.context.ResourceScope
import arrow.fx.coroutines.resource.context.install

context(_: ResourceScope)
suspend fun userProcessor(): UserProcessor =
  install(
    acquire = { UserProcessor().also { it.start() } },
    release = { processor, _ -> processor.shutdown() },
  )
```

Use the constructor inside `resource {}` or `resourceScope {}`; their context
parameter supplies the capability.

```kotlin
import arrow.fx.coroutines.resource.context.Resource
import arrow.fx.coroutines.resource.context.resource
import arrow.fx.coroutines.resource.context.resourceScope

fun processorResource(): Resource<UserProcessor> =
  resource { userProcessor() }

suspend fun process(): Unit =
  resourceScope {
    val processor = userProcessor()
    processor.toString()
  }
```

Use `context(_: ResourceScope)` when the value is only evidence that resource
installation is allowed. Use `context(resources: ResourceScope)` only when calling
receiver-style APIs directly.

## Class Design

Bad: the class opens its own resource and relies on consumers to remember a close rule.

```kotlin
class BadReportStore(path: Path) {
  private val writer = Files.newBufferedWriter(path)

  fun append(line: String) {
    writer.appendLine(line)
  }
}
```

Good: the class accepts an already-acquired private dependency, and the companion owns
the resource recipe.

```kotlin
import arrow.fx.coroutines.resource.context.Resource
import arrow.fx.coroutines.resource.context.ResourceScope
import arrow.fx.coroutines.resource.context.install
import arrow.fx.coroutines.resource.context.resource
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path

class ReportStore private constructor(
  private val writer: BufferedWriter,
) {
  fun append(line: String) {
    writer.appendLine(line)
  }

  companion object {
    context(_: ResourceScope)
    suspend fun open(path: Path): ReportStore {
      val writer = install(
        acquire = { Files.newBufferedWriter(path) },
        release = { handle, _ -> handle.close() },
      )
      return ReportStore(writer)
    }

    fun resource(path: Path): Resource<ReportStore> =
      resource { open(path) }
  }
}
```

Design APIs so consumers work with `ReportStore` inside the scope, not with
`BufferedWriter` itself. If a lower-level handle must be exposed, expose a scoped
operation such as `withWriter { ... }`, not a property.

## No Leaking

These are bugs even though they type-check:

```kotlin
import arrow.fx.coroutines.resource.context.Resource
import arrow.fx.coroutines.resource.context.bind
import arrow.fx.coroutines.resource.context.resourceScope
import arrow.fx.coroutines.use
import java.io.BufferedReader

suspend fun leakedFromScope(reader: Resource<BufferedReader>): BufferedReader =
  resourceScope {
    reader.bind()
  } // reader is closed here; caller receives a closed handle

suspend fun leakedFromUse(reader: Resource<BufferedReader>): BufferedReader =
  reader.use { it } // closed before the returned value is used
```

Return a result computed inside the scope:

```kotlin
import arrow.fx.coroutines.resource.context.Resource
import arrow.fx.coroutines.use
import java.io.BufferedReader

suspend fun firstLine(reader: Resource<BufferedReader>): String? =
  reader.use { handle -> handle.readLine() }
```

If multiple resources are acquired in a loop, install each resource as soon as it is
discovered. Do not collect raw unclosed handles and close them later.

## AutoCloseable Without Suspend

Use `arrow-autoclose` for non-suspend code that must compose multiple
`AutoCloseable` values without nested `use` blocks.

```kotlin
import arrow.AutoCloseScope
import arrow.autoCloseScope
import java.io.PrintWriter
import java.util.Scanner

context(scope: AutoCloseScope)
fun copyLines(input: String, output: String) {
  val scanner = scope.install(Scanner(input))
  val writer = scope.install(PrintWriter(output))
  for (line in scanner) writer.println(line)
}

fun copyLinesSafely(input: String, output: String): Unit =
  autoCloseScope {
    copyLines(input, output)
  }
```

Use `Resource` when you need suspend finalizers, `ExitCase`, or cancellation details.
Use `autoCloseScope` only when synchronous `AutoCloseable.close()` is enough. In both
cases, the resource handle must stay inside the scope.

## Typed Errors And ExitCase

Resource finalizers always run, but nesting with typed-error builders affects the
`ExitCase` observed by finalizers.

- `either { resourceScope { raise(error) } }` treats the logical raise as crossing the resource boundary, so finalizers observe cancellation.
- `resourceScope { either { raise(error) } }` completes the resource scope with an `Either.Left`, so finalizers observe normal completion.

Choose the nesting order based on whether finalizers should treat logical errors like
rollback-worthy abnormal exits. If finalizers are the same for every `ExitCase`, the
observable difference usually does not matter.

## Review Checklist

- Every disposable dependency has one owner and one release path.
- Acquisition is in `Resource`, `context(ResourceScope)`, `resourceScope`, `autoCloseable`, `closeable`, or `autoCloseScope`.
- No raw resource is returned from `resourceScope`, `resource`, or `.use`.
- Classes receive already-acquired dependencies through private constructors.
- Companion builders return `Resource<Class>` or require `context(ResourceScope)`.
- Finalizers are idempotent and do not assume normal completion unless checking `ExitCase`.
- Independent resources may be acquired with `parZip`; dependent resources are acquired sequentially.
- Non-suspend code uses `AutoCloseable`/`autoCloseScope` and follows the same no-leak rule.

## Representative Prompts

Use these to test the skill:

- "Refactor this Ktor client wrapper so it doesn't leak the HTTP client."
- "Design a repository that owns a database pool and per-request connections using Arrow Resource."
- "Rewrite this code that returns `resource.use { it }` so the handle cannot escape closed."
- "Provide a non-suspend AutoCloseable version using arrow-autoclose."
