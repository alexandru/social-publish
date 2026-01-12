# Social-Publish Backend (Kotlin)

This project is the backend for the Social Publish project. Its purpose is to expose an HTTP API that allows the client to publish posts on social media platforms (e.g., Mastodon, Bluesky, Twitter, LinkedIn).

It's built in Kotlin, using idiomatic functional programming and Arrow for functional data types and resource management.

## Building and testing

Project uses Gradle with Kotlin DSL.

Commands:

- To build the project:
  `./gradlew build`
- To run tests:
  `./gradlew test`
- To format the source code (required):
  `./gradlew ktlintFormat`

## Coding style

- Prefer idiomatic Kotlin constructs and direct style with `suspend` functions for effectful code, rather than monad chaining (`flatMap`).
- Use Arrow's `Resource` for resource management and `Either` for error modeling where appropriate.
- For error handling, custom sealed classes (union types) are encouraged for domain-specific errors.
- Avoid public inner classes unless they are part of a sealed class hierarchy (for union types). Prefer top-level classes and functions.
- Use packages for namespacing; avoid unnecessary nesting of classes.
- Prefer data classes and other FP techniques for modeling data over ad-hoc OOP wrappers.
- Avoid side-effectful APIs in production code unless wrapped in a `suspend` function or managed by Arrow's `Resource`.
- Prefer encapsulated, self-contained components (e.g., each social integration in its own package).
- Avoid project-wide MVC-style grouping; instead, group by feature/component.
- Components should be modular enough to be extracted into their own sub-projects or libraries.

## Development Strategy

- Practice TDD: always write or update a failing unit test before implementing or refactoring code.
- Ensure tests fail before making them pass, to verify their effectiveness.
- When refactoring, add missing unit tests before making changes.
