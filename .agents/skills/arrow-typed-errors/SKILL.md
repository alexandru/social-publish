---
name: arrow-typed-errors
description: Models Kotlin logical failures with Arrow's context-parameter Raise DSL and wrappers such as Either, nullable, Option, and Ior. Use when implementing or simplifying typed-error flows, translating error types, replacing verbose Either handling, validating input with accumulation, or selecting an error wrapper.
---

# Arrow Typed Errors

## Quick start
- Distinguish expected domain failures from exceptional faults before selecting an API.
- For new code, prefer context-parameter computations (`context(_: Raise<E>)`) and run them into a wrapper at a boundary with `either { ... }`, `nullable { ... }`, `option { ... }`, or `ior(...) { ... }`.
- Use `Either` operators for routine propagation and transformation; do not pattern match only to reconstruct `Left` or `Right`.
- Use `when` when the domain genuinely branches by case or when matching a multi-state wrapper directly communicates intent.

## Workflow
1. Model domain failures as focused sealed types.
2. Choose fail-fast composition or independent validation accumulation.
3. Implement internal logic in a `Raise<E>` context and expose an appropriate wrapper at the public boundary.
4. Bind nested typed computations and translate their errors at the layer boundary.
5. Convert only expected, recoverable exceptions into typed failures.
6. Inspect the result with a combinator or intentional case analysis at the consumer boundary.
7. Type-check examples when introducing a less familiar Arrow API or upgrading Arrow/Kotlin.
8. When changing examples, update and run the bundled `scripts/verify-examples.kt` check.

## References
- Load [references/typed-errors.md](references/typed-errors.md) for context-parameter API examples, imports, validation, wrapper usage, and the checked sample location.
- Load [references/best-practices.md](references/best-practices.md) when designing or reviewing code, especially to simplify verbose `Either` handling without turning `when` into a blanket prohibition.
