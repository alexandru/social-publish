---
name: simplify
description: Simplifies code for clarity without changing behavior. Use when code is working but overly complex, deeply nested, duplicated, or unclear.
---

# Simplify

## Overview

Simplification reduces complexity while preserving exact behavior. The goal is code that is easier to read, understand, modify, and debug — not necessarily fewer lines.

## When to Use

- After a feature works, but the implementation feels heavier than needed
- During review when readability or complexity is flagged
- When you see deep nesting, long functions, duplicated logic, or unclear names
- When refactoring code written under time pressure
- When related logic has drifted apart or become inconsistent

**When NOT to use:**

- Code is already clean and readable
- You do not understand what the code does yet
- The code is performance-critical and the "simpler" version would be measurably slower
- You're about to rewrite the module entirely
- Changes are too risky or complex for a scoped simplification

## The Five Principles

1. Preserve Behavior Exactly — keep inputs, outputs, errors, side effects, ordering, and public API signatures the same.
2. Follow Project Conventions — match local style instead of imposing outside preferences.
3. Prefer Clarity Over Cleverness — choose code that is easier to scan and reason about.
4. Maintain Balance — do not make code harder to debug, extend, or test just to reduce lines.
5. Scope to What Changed — keep the refactor tightly focused unless asked otherwise.

## Process

1. Understand — read the code's responsibility, callers, edge cases, and tests before touching it.
2. Identify — look for deep nesting, long functions, duplicated logic, dead code, misleading names, nested ternaries, boolean flags, redundant abstractions, and comments that describe obvious code.
3. Apply incrementally — make one simplification at a time and keep the diff small.
4. Verify — confirm behavior, tests, conventions, and readability all still hold.

## Guidance for the Current Codebase

- Prefer straightforward code over clever compression
- Preserve existing runtime behavior, tests, and invariants
- Favor explicit names and smaller focused helpers when they improve readability
- Keep refactors tightly scoped to the task or review feedback

## Verification Checklist

- [ ] Existing tests pass without modification
- [ ] Build/typecheck/lint still pass
- [ ] No unrelated files were refactored
- [ ] No error handling was weakened or removed
- [ ] The result is simpler to review than the original

## References

- [simplification patterns](references/simplification-patterns.md) — detailed principles, patterns, examples, and test prompts
