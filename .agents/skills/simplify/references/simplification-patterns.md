# Simplification Patterns

## Table of contents

- [Signal catalog](#signal-catalog)
- [Common rationalizations](#common-rationalizations)
- [Red flags](#red-flags)
- [Test prompts](#test-prompts)
- [Source](#source)

## Signal catalog

Scan for these patterns — each one is a concrete signal, not a vague smell.

### Structural complexity

| Pattern                    | Signal                             | Simplification                                            |
| -------------------------- | ---------------------------------- | --------------------------------------------------------- |
| Deep nesting (3+ levels)   | Hard to follow control flow        | Extract conditions into guard clauses or helper functions |
| Long functions (50+ lines) | Multiple responsibilities          | Split into focused functions with descriptive names       |
| Nested ternaries           | Requires mental stack to parse     | Replace with if/else chains, switch, or lookup objects    |
| Boolean parameter flags    | `doThing(true, false, true)`       | Replace with options objects or separate functions        |
| Repeated conditionals      | Same `if` check in multiple places | Extract to a well-named predicate function                |

### Naming and readability

| Pattern                    | Signal                                         | Simplification                                                           |
| -------------------------- | ---------------------------------------------- | ------------------------------------------------------------------------ |
| Generic names              | `data`, `result`, `temp`, `val`, `item`        | Rename to describe the content: `userProfile`, `validationErrors`        |
| Abbreviated names          | `usr`, `cfg`, `btn`, `evt`                     | Use full words unless the abbreviation is universal (`id`, `url`, `api`) |
| Misleading names           | Function named `get` that also mutates state   | Rename to reflect actual behavior                                        |
| Comments explaining "what" | `// increment counter` above `count++`         | Delete the comment — the code is clear enough                            |
| Comments explaining "why"  | `// Retry because the API is flaky under load` | Keep these — they carry intent the code can't express                    |

### Redundancy

| Pattern                   | Signal                                                       | Simplification                                            |
| ------------------------- | ------------------------------------------------------------ | --------------------------------------------------------- |
| Duplicated logic          | Same 5+ lines in multiple places                             | Extract to a shared function                              |
| Dead code                 | Unreachable branches, unused variables, commented-out blocks | Remove (after confirming it's truly dead)                 |
| Unnecessary abstractions  | Wrapper that adds no value                                   | Inline the wrapper, call the underlying function directly |
| Over-engineered patterns  | Factory-for-a-factory, strategy-with-one-strategy            | Replace with the simple direct approach                   |
| Over-abstracted patterns  | Interface with one implementation, class that could be a function, typeclass with one instance | Replace with the simplest construct that expresses the behavior (principle of least power) |
| Redundant type assertions | Casting to a type that's already inferred                    | Remove the assertion                                      |

## Common rationalizations

| Rationalization                                      | Reality                                                                                                                                               |
| ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| "It's working, no need to touch it"                  | Working code that's hard to read will be hard to fix when it breaks. Simplifying now saves time on every future change.                               |
| "Fewer lines is always simpler"                      | A 1-line nested ternary is not simpler than a 5-line if/else. Simplicity is about comprehension speed, not line count.                                |
| "I'll just quickly simplify this unrelated code too" | Unscoped simplification creates noisy diffs and risks regressions in code you didn't intend to change. Stay focused.                                  |
| "The types make it self-documenting"                 | Types document structure, not intent. A well-named function explains _why_ better than a type signature explains _what_.                              |
| "This abstraction might be useful later"             | Don't preserve speculative abstractions. If it's not used now, it's complexity without value. Remove it and re-add when needed.                       |
| "The original author must have had a reason"         | Maybe. Check git blame — apply Chesterton's Fence. But accumulated complexity often has no reason; it's just the residue of iteration under pressure. |
| "I'll refactor while adding this feature"            | Separate refactoring from feature work. Mixed changes are harder to review, revert, and understand in history.                                        |

## Red flags

- Simplification that requires modifying tests to pass (you likely changed behavior)
- "Simplified" code that is longer and harder to follow than the original
- Renaming things to match your preferences rather than project conventions
- Removing error handling because "it makes the code cleaner"
- Simplifying code you don't fully understand
- Batching many simplifications into one large, hard-to-review commit
- Refactoring code outside the scope of the current task without being asked

## Test prompts

- Simplify this working function without changing behavior; focus on deep nesting and unclear names.
- Review this recently modified code for readability-only refactors and avoid drive-by changes.
- Replace nested ternaries and duplicated conditionals with clearer control flow while preserving tests.
- Identify simplification opportunities in this module, but skip changes that are risky or behavior-changing.

## Source

Adapted from [code-simplification](https://github.com/addyosmani/agent-skills/blob/fea75b16472ba87e8c11f13a9e000c3ffdb2d1f5/skills/code-simplification/SKILL.md) by Addy Osmani with additional patterns from [code-simplifier](https://raw.githubusercontent.com/githubnext/agentics/refs/heads/main/workflows/code-simplifier.md).
