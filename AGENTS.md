# Agent Guidelines

## Overview

Social-Publish is a multi-module polyglot project. This document defines shared development standards and project-specific guidelines.

## Shared Standards

### Development Process
- **TDD-first**: Write failing tests before implementation
- **Safe refactoring**: Add missing tests before refactoring
- **Encapsulation**: Components must be extractable to standalone projects/libraries
- **Organization**: By feature/component, NOT by type (no models/views/controllers)

### Code Principles
- Functional programming patterns over imperative/OOP style when dealing with data modelling and transformations; OOP for encapsulation or dependency injection.
- Meaningful names; readable operator chains over cryptic shortcuts
- Prefer beautiful, readable and type-safe code.

---

## Project: `./backend-scala` (Scala 3)

Exposes an HTTP API that allows the client to publish posts on social media platforms (e.g., Mastodon, Bluesky, Twitter, LinkedIn).  Build in Scala 3, making use of functional programming and the Typelevel ecosystem of libraries.

### Build & Test

**Setup** (before each command):
```bash
export SBT_NATIVE_CLIENT=true
```

**Commands**:
- Build: `sbt Test/compile`
- Test: `sbt Test/compile test`
- Format (required): `sbt scalafmtAll`

### Scala-specific rules

- Braces required; NO braceless syntax (enforced via `scalafmt.conf`).
- Prefer FP-idioms for dealing with data, e.g., case classes over OOP wrapper classes
- Use `IO` for side effects; NO `unsafeRunSync` allowed
  - Wrap side-effectful / non-deterministic APIs (e.g., `UUID.randomUUID()`) in `IO`
- No public inner class/trait definitions, unless it's a union-type (sealed trait hierarchy); this includes definitions in objects; use top-level definitions in packages.
- If a namespace is needed, make packages instead of objects (Scala 3 admits top-level definitions even for `def` or `val`).
- Readable chains (`flatMap`, for-comprehensions) over operators (`*>`, `>>`)
- Implicits/givens in companion objects for coherence / global visibility (no orphaned instances, no imports)

### Package Structure

Organize by component / let encapsulation drive the organization, e.g.:
- `socialpublish.integrations.twitter`
- `socialpublish.integrations.mastodon`
- `socialpublish.http` (API server)

---

## Project: `./backend` (TypeScript/Node.js)

Guidelines TBD.

---

## Project: `./frontend` (TypeScript/React)

Guidelines TBD.
