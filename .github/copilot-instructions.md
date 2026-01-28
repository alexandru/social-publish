# Social-Publish (Kotlin)

This project is the Social Publish application. Its purpose is to expose an HTTP API that allows the client to publish posts on social media platforms (e.g., Mastodon, Bluesky, Twitter, LinkedIn).

It's built in Kotlin, using idiomatic functional programming and Arrow for functional data types and resource management.

## Project Architecture

This is a Kotlin multiplatform project with:

- **Backend**: Ktor server with Arrow for functional programming (in `backend/` directory)
  - Package structure: `socialpublish.backend.*`
  - Main modules: `db/`, `integrations/`, `models/`, `modules/`, `server/`
  - Database: SQLite (via Exposed ORM)
  - HTTP server: Ktor on port 3000
  
- **Frontend**: Compose for Web (Kotlin/JS) (in `frontend/` directory)
  - Development server runs on port 3002
  - Communicates with backend API
  
- **Build**: Gradle with Kotlin DSL
  - Root `build.gradle.kts` configures plugins and common settings
  - Subprojects: `backend/` and `frontend/` each have their own `build.gradle.kts`

## Building and Testing

Project uses Gradle with Kotlin DSL. Use the Makefile for common tasks:

### Development

- Start full development environment (backend + frontend with hot reload):
  ```bash
  make dev
  ```

- Run backend only (port 3000):
  ```bash
  make dev-backend
  # or
  ./gradlew :backend:run --args="start-server"
  ```

- Run frontend only (port 3002):
  ```bash
  make dev-frontend
  # or
  ./gradlew :frontend:jsBrowserDevelopmentRun --continuous
  ```

### Building and Testing

- Build the entire project:
  ```bash
  make build
  # or
  ./gradlew build
  ```

- Run all tests:
  ```bash
  make test
  # or
  ./gradlew test
  ```

- Run tests for specific module:
  ```bash
  ./gradlew :backend:test
  ./gradlew :frontend:test
  ```

### Code Quality

- Check code formatting:
  ```bash
  make lint
  # or
  ./gradlew ktfmtCheck
  ```

- Format the source code (required before committing):
  ```bash
  make format
  # or
  ./gradlew ktfmtFormat
  ```

### Docker

 - Build and run JVM Docker image locally:
  ```bash
  make docker-run-jvm
  ```

## Coding Style

- Prefer idiomatic Kotlin constructs and direct style with `suspend` functions for effectful code, rather than monad chaining (`flatMap`).
- Use Arrow's `Resource` for resource management and `Either` for error modeling where appropriate.
- Avoid `runBlocking` (except for `main`). Coroutines (suspended functions) should be used idiomatically.
- For error handling, custom sealed classes (union types) are encouraged for domain-specific errors.
- Avoid public inner classes unless they are part of a sealed class hierarchy (for union types). Prefer top-level classes and functions.
- Use packages for namespacing; avoid unnecessary nesting of classes.
- Prefer data classes and other FP techniques for modeling data over ad-hoc OOP wrappers.
- Avoid side-effectful APIs in production code unless wrapped in a `suspend` function or managed by Arrow's `Resource`.
- Prefer encapsulated, self-contained components (e.g., each social integration in its own package).
- Avoid project-wide MVC-style grouping; instead, group by feature/component. Prefer colocating types with the feature that uses them.
  - Don't introduce silly `models/` or `views/` packages
- Components should be modular enough to be extracted into their own sub-projects or libraries.
- Use good imports (no fully qualified names).
- Write comments, but keep them meaningful:
  - No comments on what the Agent did.
  - No comments on what function signatures already say.
  - No comments on configuration that can always change.
  - No new comments explaining why code was removed.
  - Document invariants and non-obvious decisions that aren't clear from signatures.
 - Never delete the user's comments.

### Frontend (Compose for Web)

- **State Hoisting**: Follow the State Hoisting best practice where state is kept by a component's caller, while the component remains stateless, communicating back and forth with the caller via events. Model state with clean and immutable data structures.
  - Components should receive state as parameters and communicate changes via callback functions
  - Avoid internal mutable state in components when that state needs to be shared or persisted
  - See https://developer.android.com/develop/ui/compose/state for guidance

## Development Strategy

- Practice TDD: always write or update a failing unit test before implementing or refactoring code.
- Ensure tests fail before making them pass, to verify their effectiveness.
- When refactoring, add missing unit tests before making changes.
- Run tests frequently during development to catch regressions early.
- Use `make format` before committing to ensure code follows the project's style guidelines.

## Key Technologies

- **Kotlin 2.3.0**: Multiplatform with JVM and JS targets
- **Ktor**: HTTP server framework (backend)
- **Arrow**: Functional programming library
- **Exposed**: SQL framework for database access
- **SQLite**: Database engine
- **Compose for Web**: Frontend framework
- **ktfmt**: Code formatting (Google Kotlin style)

## Common Workflows

### Adding a New Social Media Integration

1. Create a new package in `backend/src/main/kotlin/socialpublish/backend/integrations/`
2. Implement the API client using Arrow's `Resource` for HTTP client management
3. Add configuration to `AppConfig.kt`
4. Add integration tests in `backend/src/test/kotlin/socialpublish/backend/integrations/`
5. Update the main publishing logic to include the new platform

### Modifying the Database Schema

1. Update the schema definition in relevant `*Database.kt` file under `backend/src/main/kotlin/socialpublish/backend/db/`
2. Add migration logic if needed (check existing migration patterns)
3. Add or update tests in `backend/src/test/kotlin/socialpublish/backend/db/`
4. Test migrations manually with a development database

### Adding a New API Endpoint

1. Create or update module in `backend/src/main/kotlin/socialpublish/backend/modules/`
2. Register the route in `Main.kt`
3. Add request/response models in `backend/src/main/kotlin/socialpublish/backend/models/`
4. Add tests in `backend/src/test/kotlin/socialpublish/backend/modules/`
5. Document the endpoint in `test.http` if it's a public API

## Testing Guidelines

- Backend tests are in `backend/src/test/kotlin/socialpublish/backend/`
- Test structure mirrors source structure
- Use descriptive test names that explain the behavior being tested
- Mock external API calls to avoid dependencies on external services
- Test both success and error cases
- For integration tests with real APIs, use test credentials or mocks

## Environment Variables

Key environment variables used by the application (see `.envrc.sample` and README.md):

- `BASE_URL`: Server base URL
- `SERVER_AUTH_USERNAME`, `SERVER_AUTH_PASSWORD`: Basic auth credentials
- `JWT_SECRET`: JWT signing secret
- `DB_PATH`: SQLite database file path
- `UPLOADED_FILES_PATH`: File upload directory
- Social platform credentials: `BSKY_*`, `MASTODON_*`, `TWITTER_*`

## Troubleshooting

### Build Failures

- Ensure Kotlin version is compatible (check `build.gradle.kts`)
- Run `./gradlew clean build` to rebuild from scratch
- Check that all dependencies are resolved: `./gradlew dependencies`

### Test Failures

- Run specific test to isolate: `./gradlew :backend:test --tests ClassName.testName`
- Check test output in `backend/build/reports/tests/test/index.html`
- Ensure test database is clean (tests should be isolated)

### Development Server Issues

- Check if ports 3000 (backend) or 3002 (frontend) are already in use
- Verify environment variables are set (use `.envrc` with direnv)
- Check logs for startup errors
- Ensure database path is writable
