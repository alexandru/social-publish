# Simplification Patterns

## Table of contents

- [Principles](#principles)
- [Process](#process)
- [Signal catalog](#signal-catalog)
- [Examples](#examples)
- [Test prompts](#test-prompts)

## Principles

### 1. Preserve behavior exactly

Simplification changes structure, not outcomes. Keep:

- inputs and outputs
- error behavior
- side effects and ordering
- edge-case handling
- public contracts and expectations
- public API signatures (exported functions, method names, parameter lists, return types)

If a refactor changes behavior, it is no longer a simplification.

### 2. Follow project conventions

Make the code look like the rest of the codebase. Reuse existing patterns for naming, function shape, error handling, and helper placement. Do not trade local consistency for a personal preference.

### 3. Prefer clarity over cleverness

Choose the version that is easiest to read on a first pass. Favor direct control flow, descriptive names, and small helpers over dense expressions that require unpacking.

### 4. Maintain balance

Simpler should also mean easier to debug and extend. Do not remove structure that carries meaning, collapses distinct paths that deserve separation, or hide logic inside a generic helper that is harder to inspect.

### 5. Scope to what changed

Keep the refactor focused on the code under review or the recently modified area. Avoid drive-by cleanup in unrelated files unless the task explicitly asks for it.

## Process

### 1. Understand

Read the code in context before changing anything. Identify the responsibility, the callers, the downstream effects, and the tests that lock behavior in place.

### 2. Identify

Look for simplification signals:

- deep nesting
- long functions
- duplicated logic
- dead code
- misleading or generic names
- nested ternaries
- boolean flag arguments
- redundant abstractions
- comments that merely restate obvious code

### 3. Apply incrementally

Make the smallest useful refactor first. Prefer one clear transformation at a time, then re-check behavior before moving on.

### 4. Verify

Confirm the simplified code still behaves the same, still matches project style, and is easier to understand than before.

## Signal catalog

- **Deep nesting**: guard clauses, early returns, or small helpers can flatten the flow.
- **Long functions**: split by responsibility, not by arbitrary line count.
- **Duplicated logic**: extract the repeated decision or transformation once.
- **Dead code**: remove unused branches, parameters, and helpers.
- **Misleading names**: rename to reflect what the code actually does.
- **Nested ternaries**: replace with readable branching.
- **Boolean flag arguments**: split into clearer named functions or a better API.
- **Redundant abstractions**: remove wrappers that add indirection without value.
- **Obvious comments**: delete comments that repeat the code; keep comments that explain intent, constraints, or tradeoffs.

## Examples

The following samples are language-specific illustrations. They are not prescriptive rules — always defer to the project's own conventions (Principle 2).

### JavaScript / TypeScript

**Prefer `function` over arrow for top-level**

Before:
```js
const fetchData = async (id: string): Promise<Data> => {
  const result = await api.get(id);
  return result;
};
```
After:
```js
async function fetchData(id: string): Promise<Data> {
  return await api.get(id);
}
```

**Replace nested ternary with readable branching**

Before:
```ts
const label = isLoading ? "Loading..." : hasError ? errorMessage : data?.name ?? "Untitled";
```
After:
```ts
function getLabel(state: State): string {
  if (state.isLoading) return "Loading...";
  if (state.hasError) return state.errorMessage;
  return state.data?.name ?? "Untitled";
}
```

**Remove useless try/catch that only re-throws**

Before:
```ts
try {
  return process(input);
} catch (e) {
  throw e;
}
```
After:
```ts
return process(input);
```

### Go

**`interface{}` → `any`**

Before:
```go
func process(data map[string]interface{}) interface{} {
```
After:
```go
func process(data map[string]any) any {
```

**Semantic type alias for domain concepts**

Before:
```go
func calculateDiscount(base float64, isPremium bool) float64 {
```
After:
```go
type Price float64

func calculateDiscount(base Price, isPremium bool) Price {
```

**Prefer small, focused files**

When a single file spans many unrelated concerns, split it apart. Aim for files in the 200-500 line range as a signal, not a hard rule.

### Python

**Replace overly-dense expression with named steps**

Before:
```python
result = {k: v.upper() for k, v in sorted(filter(lambda x: x[0].startswith("user_"), data.items()), key=lambda x: x[1])}
```
After:
```python
user_items = {k: v for k, v in data.items() if k.startswith("user_")}
sorted_items = sorted(user_items.items(), key=lambda item: item[1])
result = {k: v.upper() for k, v in sorted_items}
```

**Add type hints to clarify intent**

Before:
```python
def apply_discount(base, loyalty_years):
    rate = min(0.05 * loyalty_years, 0.30)
    return base * (1 - rate)
```
After:
```python
def apply_discount(base: float, loyalty_years: int) -> float:
    rate: float = min(0.05 * loyalty_years, 0.30)
    return base * (1 - rate)
```

### C# / .NET

**Pattern matching over type casting**

Before:
```csharp
var person = obj as Person;
if (person != null)
{
    Console.WriteLine(person.Name);
}
```
After:
```csharp
if (obj is Person person)
{
    Console.WriteLine(person.Name);
}
```

**`async`/`await` over `.Result` or `.Wait()`**

Before:
```csharp
var data = GetDataAsync().Result;
```
After:
```csharp
var data = await GetDataAsync();
```

**File-scoped namespace over block-scoped**

Before:
```csharp
namespace App.Services
{
    public class Parser
    {
    }
}
```
After:
```csharp
namespace App.Services;

public class Parser
{
}

**Use `var` only when the type is obvious from the right-hand side**

Before:
```csharp
var result = GetData();
var items = new List<string>();
```
After:
```csharp
var items = new List<string>();
DataResult result = GetData();
```

**Annotate nullability explicitly**

Before:
```csharp
public string FindUser(int id)
{
    return _users.FirstOrDefault(u => u.Id == id)?.Name;
}
```
After:
```csharp
public string? FindUser(int id)
{
    return _users.FirstOrDefault(u => u.Id == id)?.Name;
}

## Source

Adapted from [code-simplification](https://github.com/addyosmani/agent-skills/blob/main/skills/code-simplification/SKILL.md) by Addy Osmani with additional patterns from [code-simplifier](https://raw.githubusercontent.com/githubnext/agentics/refs/heads/main/workflows/code-simplifier.md).

## Test prompts

- Simplify this working function without changing behavior; focus on deep nesting and unclear names.
- Review this recently modified code for readability-only refactors and avoid drive-by changes.
- Replace nested ternaries and duplicated conditionals with clearer control flow while preserving tests.
- Identify simplification opportunities in this module, but skip changes that are risky or behavior-changing.
