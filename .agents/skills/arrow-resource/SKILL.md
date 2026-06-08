---
name: arrow-resource
description: Kotlin + Arrow Resource lifecycle discipline with `Resource`, `ResourceScope`, `resourceScope`, and context parameters. Use for safe acquisition/release of files, streams, DB pools/connections, HTTP clients, background scopes, or multipart parts; preventing resource leaks and use-after-close bugs; designing companion `Resource` builders; using `arrow-autoclose` when only synchronous `AutoCloseable` cleanup is possible; composing resources with typed errors, cancellation, or parallel acquisition.
---

# Arrow Resource (Kotlin)

## Quick start
- Treat every disposable handle as scoped: wrap it in `Resource` and acquire it only inside `resourceScope`, `resource {}`, or `Resource.use`.
- Prefer `arrow.fx.coroutines.resource.context` imports for context-parameter-friendly `resource`, `resourceScope`, `bind`, `install`, and `autoCloseable`.
- Never return a disposable value from `resourceScope { ... }`, `resource { ... }`, or `resource.use { ... }`; that returns a closed handle.
- Classes must not allocate owned resources in ordinary constructors. Use a companion builder returning `Resource<Class>` or requiring `context(ResourceScope)`.
- If suspend `Resource` cannot be used, use `arrow-autoclose`/`AutoCloseable` with the same no-leak discipline.
- Read `references/resource.md` before writing examples, reviews, or new APIs.

## Workflow
1. Identify every owned disposable dependency and its release action.
2. Move acquisition into a `Resource` recipe or `context(ResourceScope)` builder.
3. Compose resources into higher-level resources with `.bind()` or context-parameter builders.
4. Keep resource handles private to the scoped object; expose operations, not the raw handle.
5. Execute at lifecycle boundaries with `resourceScope`/`.use`, and keep finalizers idempotent.

## Usage guidance
- Prefer `Resource` over `use`/`try/finally` for suspend finalizers, cancellation awareness, composition, or multiplatform code.
- Use context parameters for scoped capabilities: `context(_: ResourceScope) suspend fun acquire...`.
- Use `ExitCase` when release behavior depends on success, failure, or cancellation.
- `Resource` is FP-style ownership: acquisition and finalization are values/effects that compose; hidden side-effectful constructors are a design smell.
- When changing examples, update and run the bundled `scripts/verify-examples.kt` check.

## References
- Load `references/resource.md` for rules, source links, and typechecked examples.
