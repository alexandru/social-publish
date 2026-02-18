# Multi-User Support Plan

## Goal

Transform the app from a single-user env-var-configured system to a multi-user architecture where each user owns their data and manages social network credentials via the UI.

## Current Branch Status Snapshot

### Database

- **Migrations 5-7**: auto-creates `admin` user on first run with a randomly-generated password (logged once at WARN); adds `settings TEXT` to `users`; adds `user_uuid NOT NULL` + scoped indexes to `documents`/`uploads`; drops old unscoped indexes; backfills existing rows to the admin user
- `Migration` redesigned - each migration carries a `testIfApplied` predicate; `applyDataMigrations()` removed - all DDL+DML lives in a single typed step:

```kotlin
data class Migration(
    val execute: suspend (SafeConnection) -> Unit,
    val testIfApplied: suspend (SafeConnection) -> Boolean,
)
```

- `Document.userUuid` and `Upload.userUuid` are non-nullable in both Kotlin models and SQL schema

### Auth

- `SERVER_AUTH_USERNAME` / `SERVER_AUTH_PASSWORD` env vars removed - credentials live in `users` table
- `verifyToken` + `getUserUuidFromToken` consolidated into `verifyTokenPayload(): VerifiedToken`
- Login response carries `configuredServices` (includes actual OAuth connection state for Twitter/LinkedIn) - `hasAuth`/`AuthStatus` removed
- Login with unknown username -> **403 Forbidden**
- Ktor `AttributeKey` plugin resolves `userUuid` + `UserSettings` once per authenticated request - no repeated DB lookups across route handlers

### User Settings

`UserSettings` stored as JSON in `users.settings`, using existing config types directly (all made `@Serializable`):

```kotlin
@Serializable
data class UserSettings(
    val bluesky: BlueskyConfig? = null,
    val mastodon: MastodonConfig? = null,
    val twitter: TwitterConfig? = null,
    val linkedin: LinkedInConfig? = null,
    val llm: LlmConfig? = null,
)
```

### Settings API

- **`GET /api/account/settings`** - returns `AccountSettingsView`: real values for non-sensitive fields, `****` for set sensitive fields, `null` for unconfigured sections
- **`PATCH /api/account/settings`** - typed `UserSettingsPatch` built on `Patched<T>` for proper RFC 7396 semantics: absent field = keep existing, `null` = remove section, value = update

```kotlin
@Serializable(with = PatchedSerializer::class)
sealed interface Patched<out T> {
    data class Some<out T>(val value: T?) : Patched<T>
    data object Undefined : Patched<Nothing>
}
```

### Server Architecture

- All modules (`BlueskyApiModule`, `MastodonApiModule`, `TwitterApiModule`, `LinkedInApiModule`, `LlmApiModule`) instantiated **once at startup**; per-user config passed as function parameter per call
- Twitter/LinkedIn/LLM Ktor route logic extracted from module classes into `TwitterRoutes.kt`, `LinkedInRoutes.kt`, `LlmRoutes.kt`
- OAuth tokens for Twitter and LinkedIn scoped per user (`twitter-oauth-token:{userUuid}`)
- `server/utils.kt` provides `respondWithInternalServerError`, `respondWithNotFound`, `respondWithForbidden` helpers
- `SettingsRoutes` takes `userUuid: UUID` directly - no dependency on `AuthRoutes`
- `change-password` CLI command added

### Frontend

- `models/UserSettings.kt` deleted - credentials must not be round-tripped to the browser
- **`/account` page**: settings form for all integrations; single `SettingsFormState` with proper state hoisting; non-sensitive fields pre-populated from server; sensitive fields show `****` placeholder when a value exists server-side; PATCH body omits absent keys
- **Publish form**: all service checkboxes always shown; unconfigured services rendered **disabled** with link to `/account`
- `Storage.kt`: `hasAuth` removed; `configuredServices` drives publish form availability

## Review-Driven Verification Checklist

These items come from PR review `#pullrequestreview-3819353783` and should be verified against current branch state.

- [x] Verify migration framework uses `Migration(execute, testIfApplied)` end-to-end and no `applyDataMigrations()` remains (`discussion_r2821836435`, `discussion_r2821851115`)
- [x] Verify migration 5 creates initial admin user directly inside migration execution (`discussion_r2821836435`)
- [x] Verify migrations backfill ownership before enforcing `user_uuid NOT NULL` (`discussion_r2821836435`)
- [x] Verify old unscoped `documents`/`uploads` indexes are dropped and only `user_uuid`-scoped indexes remain (`discussion_r2821839470`)
- [x] Verify initial admin password is random, logged once, and no insecure static default remains (`discussion_r2821847803`)
- [ ] Verify `change-password` CLI command exists and works (`discussion_r2821847803`)
- [ ] Verify `userUuid` is mandatory/non-null across schema, models, DB methods, and callers (`discussion_r2821852449`, `discussion_r2821855624`, `discussion_r2821857516`, `discussion_r2821859256`, `discussion_r2821860580`, `discussion_r2821863118`, `discussion_r2821867526`, `discussion_r2821868460`)
- [x] Verify settings parse failures in `UsersDatabase` log at `error` level (not `warn`) (`discussion_r2821871984`)
- [x] Verify `UserSettings` uses config types directly and does not duplicate mirror `*UserSettings` config wrappers (`discussion_r2821906798`)
- [ ] Verify `UserSettings` location/organization aligns with requested module boundaries and current conventions (`discussion_r2822180011`)
- [x] Verify token helpers are consolidated into a single payload method (e.g. `verifyTokenPayload`) returning username + user UUID (`discussion_r2821913018`)
- [ ] Verify login unknown-user path returns `403 Forbidden` and not `500` (`discussion_r2821922702`)
- [x] Verify login response exposes `configuredServices` semantics only (no stale `hasAuth`/`AuthStatus`) (`discussion_r2822151945`)
- [x] Verify route auth extraction is pure and route dependencies are decoupled (`SettingsRoutes` not depending on `AuthRoutes`) (`discussion_r2822163550`, `discussion_r2822225532`, `discussion_r2822230154`)
- [x] Verify reusable response helpers are used from `server/utils.kt` (`discussion_r2822214254`)
- [x] Verify LLM endpoint logic is fully extracted out of `Server.kt` into `LlmRoutes.kt` (`discussion_r2822239191`)
- [x] Verify per-request user context loading avoids repeated DB lookups in route handlers (`discussion_r2822256611`, `discussion_r2822262583`, `discussion_r2822268205`)
- [x] Verify service modules are startup-singletons and not reconstructed per-request in route handlers (`discussion_r2822279207`, `discussion_r2822291883`, `discussion_r2822295718`)
- [x] Verify `PublishRoutes` signature and internals consistently use provided dependencies and no dead parameters remain (`discussion_r2822169489`)
- [x] Verify Twitter route/module boundary is clean: no `ApplicationCall` in module API and HTTP logic moved to `TwitterRoutes.kt` (`discussion_r2822312687`)
- [x] Verify Twitter OAuth persistence/search keys are strictly user-scoped and never use nullable/default user IDs (`discussion_r2822320840`)
- [x] Verify LinkedIn route/module boundary is clean: no `ApplicationCall` in module API and HTTP logic moved to `LinkedInRoutes.kt` (`discussion_r2822338907`)
- [x] Verify LinkedIn OAuth persistence/search keys are strictly user-scoped and never use nullable/default user IDs (`discussion_r2822338907`)
- [ ] Verify `DocumentsDatabase` has no nullable/default `userUuid` in write/query APIs (`discussion_r2822324356`, `discussion_r2822325762`)
- [x] Verify obsolete/non-actionable historical comment was removed from `ServerConfig.kt` (`discussion_r2822341771`)
- [x] Verify frontend does not define/store credential-bearing `UserSettings` models (`models/UserSettings.kt` removed and no equivalent leaks) (`discussion_r2822353132`, `discussion_r2822355137`)
- [x] Verify `/account` UI uses state hoisting with a single immutable form state object (not many independent remembered vars) (`discussion_r2822360794`, `discussion_r2822362512`, `discussion_r2822369315`)
- [x] Verify publish form keeps all service checkboxes visible and disables unconfigured services (instead of hiding) (`discussion_r2822377160`)
- [x] Verify publish form does not show an incorrect "no services configured" warning given RSS availability (`discussion_r2822380411`)
- [x] Verify frontend storage/auth model relies on `configuredServices` only and does not keep redundant `hasAuth` (`discussion_r2822385702`)

## TODO (Implementation Plan)

This section is intentionally detailed so an agent with zero context can execute safely.

Rules for execution:

- Always follow TDD from `AGENTS.md`: add/fix failing tests first, then implement.
- Keep this plan and checklist in sync while working:
  - mark plan TODOs as done when implementation + tests pass
  - mark the corresponding checklist item(s) in `Review-Driven Verification Checklist` as `[x]`
- Do not remove existing comments authored by the user.

### Scope Summary (must all be implemented)

1. Finish remaining checklist items that are still unchecked.
2. Implement the new auth requirement: `users.password_hash` nullable; null means disabled login.
3. Address additional review `#pullrequestreview-3821349420` comments if valid.
4. Address already-identified functional gaps (masked secret overwrite, upload user-scope dedup, frontend OAuth readiness race/state mismatch).

### Review `#pullrequestreview-3821349420` (investigate + fix)

- `discussion_r2823641232`: Twitter authorize route should also accept JWT from cookie on browser redirect flow.
- `discussion_r2823641239`: LinkedIn authorize route should also accept JWT from cookie on browser redirect flow.
- `discussion_r2823641243`: Settings PATCH must preserve existing secret when payload contains masked sentinel (`"****"`).
- `discussion_r2823641249`: Frontend configured-service state for Twitter/LinkedIn must represent OAuth readiness, not only credential presence.

### Work Plan by Phase

#### Phase 0 - Baseline and safety checks

- [ ] Capture baseline failing/passing status of targeted tests (auth routes, settings routes, db migrations, files db, account page logic tests if present).
- [ ] Create a mapping table in notes/commit message drafts from each unchecked checklist item to concrete file changes.

#### Phase 1 - Password-nullability architecture change (new requirement)

Goal: user password can be absent (`NULL`) and this disables login for that user.

Primary files to inspect/update:

- `backend/src/main/kotlin/socialpublish/backend/db/migrations.kt`
- `backend/src/main/kotlin/socialpublish/backend/db/UsersDatabase.kt`
- `backend/src/main/kotlin/socialpublish/backend/db/models.kt`
- `backend/src/main/kotlin/socialpublish/backend/server/routes/AuthRoutes.kt`
- `backend/src/main/kotlin/socialpublish/backend/Main.kt`
- relevant tests under `backend/src/test/kotlin/socialpublish/backend/**`

Tasks:

- [ ] Add/adjust migration(s) so `users.password_hash` is nullable in all supported DB states.
  - Existing DBs with non-null column must migrate safely.
  - Fresh DB path must end with nullable column.
- [ ] Update migration 5 behavior:
  - create initial `admin` user with `password_hash = NULL`
  - remove random password generation and password logging.
- [ ] Update domain/data model to allow nullable password hash where required.
- [ ] Update login/auth logic:
  - if user exists but password hash is null => deny authentication (use expected status semantics below)
  - no BCrypt verification attempted on null hash.
- [ ] Ensure `change-password` command can set password for users with null hash.
- [ ] Decide and implement exact response contract for disabled-password users (documented in tests).

Acceptance criteria:

- Login for null-password user is denied deterministically and tested.
- Setting password via CLI enables successful login.
- No random default admin password is generated or logged.

#### Phase 2 - Complete remaining checklist items

##### 2.1 `change-password` command exists and works

- [ ] Add/expand tests around `ChangePasswordCommand` behavior (success, unknown user, nullable-password user).
- [ ] Add reproducible verification steps in plan notes or test names.
- [ ] After validation, mark checklist item `discussion_r2821847803` done.

##### 2.2 Unknown username login returns 403

- [ ] Add failing test in auth route tests asserting unknown username => `403 Forbidden`.
- [ ] Implement route behavior in `AuthRoutes` while preserving no-user-enumeration policy decisions documented by project owner.
- [ ] Verify no `500` path for unknown username case.
- [ ] Mark checklist item `discussion_r2821922702` done.

##### 2.3 Mandatory user ownership and `DocumentsDatabase` nullable/default cleanup

- [ ] Audit DB APIs for nullable/default `userUuid` in write/query flows (especially `DocumentsDatabase`, `PostsDatabase`, files access paths).
- [ ] Remove nullable/default user UUID parameters except intentionally public read paths (if any).
- [ ] For any intentional exception (e.g. public RSS), replace nullable argument with explicit dedicated method to avoid ambiguous API.
- [ ] Update all callers and tests.
- [ ] Mark checklist items tied to `discussion_r2821852449` ... `discussion_r2821868460` and `discussion_r2822324356`, `discussion_r2822325762` done.

##### 2.4 `UserSettings` location and module boundaries

- [ ] Verify placement against AGENTS architecture guidance and reviewer request (`discussion_r2822180011`).
- [ ] If refactor needed, move with minimal churn and update imports/tests.
- [ ] Mark checklist item done once justified by code and conventions.

#### Phase 3 - Address latest review + known functional bugs

##### 3.1 OAuth authorize routes must accept cookie JWT

- [ ] Add failing tests for Twitter and LinkedIn authorize routes where JWT is provided via cookie only.
- [ ] Update token extraction logic in routes to accept cookie source consistently with existing auth extraction helper.
- [ ] Confirm no regression for header/query token flows.
- [ ] Addresses `discussion_r2823641232`, `discussion_r2823641239`.

##### 3.2 Enforce strict PATCH semantics for secrets (frontend must never send `"****"`)

- [ ] Frontend request-contract rule:
  - Sensitive fields must be omitted from PATCH payload when unchanged (`Patched.Undefined` via missing JSON keys).
  - `"****"` is display-only and must never be serialized into outbound PATCH JSON.
  - `"****"` should not be bound as input `value`; use placeholder/help text only.
- [ ] Add/update frontend tests for `toPatchBody()` (or equivalent) asserting unchanged secret fields are absent (undefined/missing key), not `null`, not `"****"`.
- [ ] Add/update frontend UI tests (or deterministic component checks) asserting masked state is represented as placeholder/hint only.
- [ ] Keep backend merge behavior aligned with `Patched` semantics:
  - absent field => keep existing
  - explicit `null` => clear/remove section as currently designed
  - explicit value => update
- [ ] Optional hardening (recommended): if backend receives literal `"****"` in secret fields, reject with `400` validation error to catch client bugs early.
- [ ] Addresses `discussion_r2823641243`.

##### 3.3 Frontend configured-service semantics for OAuth readiness

- [ ] Add/update frontend tests for `/account` state updates to ensure Twitter/LinkedIn readiness remains false until OAuth status confirms authorized.
- [ ] Remove race-prone or contradictory writes to storage from settings-only responses.
- [ ] Ensure save/load flows produce consistent `configuredServices` semantics with backend `LoginResponse` and status endpoints.
- [ ] Addresses `discussion_r2823641249`.

##### 3.4 File dedup user scoping

- [ ] Add failing backend DB test reproducing cross-user dedup collision in `FilesDatabase.createFile`.
- [ ] Decide policy:
  - default required policy: per-user dedup (recommended for current architecture)
  - if shared dedup is intentionally desired, implement explicit ownership/authorization model and document it.
- [ ] Implement chosen policy and update tests.

#### Phase 4 - Final verification and checklist closure

- [ ] Run focused tests during each phase; then run full suites:
  - `./gradlew :backend:test`
  - `./gradlew :frontend:test`
- [ ] Run formatting/lint checks and fix issues:
  - `make format`
  - `make lint`
- [ ] Re-open `Review-Driven Verification Checklist` and mark every implemented item `[x]`.
- [ ] For any intentionally deferred item, leave unchecked and document explicit reason.

### Traceability Matrix (Checklist -> Implementation)

- [ ] `discussion_r2821847803` -> Phase 1 + Phase 2.1
- [ ] `discussion_r2821852449`..`discussion_r2821868460` -> Phase 2.3
- [ ] `discussion_r2822180011` -> Phase 2.4
- [ ] `discussion_r2821922702` -> Phase 2.2
- [ ] `discussion_r2822324356`, `discussion_r2822325762` -> Phase 2.3
- [ ] `discussion_r2823641232`, `discussion_r2823641239` -> Phase 3.1
- [ ] `discussion_r2823641243` -> Phase 3.2
- [ ] `discussion_r2823641249` -> Phase 3.3

### Done Definition

An item is considered done only when all are true:

- code implemented,
- tests added/updated and passing,
- no regression in related modules,
- corresponding checklist checkbox updated,
- this TODO entry updated to `[x]`.

## Remaining Future Work (Beyond Current PR Scope)

- Refresh-token rotation and stronger per-user session lifecycle policies
- Admin UI for user management (create/edit/disable users)
