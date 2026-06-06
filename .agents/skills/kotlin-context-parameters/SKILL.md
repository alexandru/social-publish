---
name: kotlin-context-parameters
description: Kotlin context parameters (`context(...)`) for scoped dependencies, DSL scopes, and migrations from context receivers. Use when designing, reviewing, or refactoring Kotlin code that uses implicit contextual values.
---

# Kotlin Context Parameters

## Quick start

- Declare dependencies with `context(name: Type)` before a function or property.
- Introduce values at boundaries with `context(value) { ... }` or from an existing implicit receiver such as `with(value) { ... }`.
- Use named context parameters for services you call directly; use `_` for marker/scope capabilities used only for resolution.
- Prefer small, explicit contexts. Avoid having multiple same-typed values available at the same scope level.
- Read `references/context-parameters.md` before writing examples or migration advice.

## Workflow

1. Identify the contextual capability: service dependency, DSL/scope marker, typeclass-like evidence, or migration from context receivers.
2. Decide names: named parameter when code needs the value; `_` plus bridge functions when the context should only unlock operations.
3. Add a boundary that supplies the context (`context(...) {}` or a wrapper accepting a `context(Type) () -> A` block).
4. Check restrictions: no constructor/class contexts, no context-property initializers/backing fields/delegation.
5. Compile-test syntax with the target Kotlin version.

## Rules of thumb

- Context resolution is by type at the call site, not by parameter name.
- Context values come from in-scope context parameters and implicit receivers.
- If two compatible values are available at the same scope level, expect ambiguity.
- Do not present proposal-only features as available unless verified with the user's Kotlin version.
- For contextual function references and explicit context arguments, use lambdas or compile-test first; support has lagged the proposal in tested Kotlin builds.

## Output expectations

- Show call sites, not just declarations.
- Prefer examples that compile on the target Kotlin version, or clearly mark untested/proposal-only behavior.

## References

- Load `references/context-parameters.md` for examples, restrictions, migration notes, and test prompts.
