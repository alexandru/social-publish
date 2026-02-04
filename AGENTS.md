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
- Sleeping threads (e.g., `IO.sleep`, `Thread.sleep`) is banned in testing. Find better ways for simulating time or for ordering concurrent events.

---

## Agent Skills

Agents should be aware of and apply the following Scala/Cats‑Effect skills when working in the `./backend` codebase. These provide focused best practices and patterns that agents must follow for correctness and consistency.

- `cats-effect-io` — Guidance for using Cats Effect `IO`: wrap side effects in `IO`, choose appropriate typeclasses (`Sync`/`Async`/`Temporal`/`Concurrent`), handle blocking I/O safely, compose fibers and concurrency without `unsafeRunSync`.
- `cats-effect-resource` — Patterns for `Resource` lifecycle management: acquire/release safety, composing resources (including parallel acquisition), and proper cancellation semantics when working with files, streams, clients, pools, and background fibers.
- `cats-mtl-typed-errors` — Recommendations for typed domain errors using Cats MTL `Raise/Handle`: design domain error types without pervasive `EitherT`, keep composition with Cats Effect straightforward, and prefer typed error handling over unchecked exceptions.

These skills codify project expectations for effect handling, resource safety, and error design. When making edits to Scala files in `./backend`, agents must follow the conventions above and consult the corresponding skill guidance where applicable.

## Project: `./backend` (Scala 3)

Exposes an HTTP API that allows the client to publish posts on social media platforms (e.g., Mastodon, Bluesky, Twitter, LinkedIn). Build in Scala 3, making use of functional programming and the Typelevel ecosystem of libraries.

### Build & Test

**Commands**:

- Build: `sbt Test/compile`
- Test: `sbt ";Test/compile;test"`
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

## Project: `./frontend` (TypeScript/React)

Guidelines TBD.
