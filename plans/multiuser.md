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

- [ ] Verify migration framework uses `Migration(execute, testIfApplied)` end-to-end and no `applyDataMigrations()` remains (`discussion_r2821836435`, `discussion_r2821851115`)
- [ ] Verify migration 5 creates initial admin user directly inside migration execution (`discussion_r2821836435`)
- [ ] Verify migrations backfill ownership before enforcing `user_uuid NOT NULL` (`discussion_r2821836435`)
- [ ] Verify old unscoped `documents`/`uploads` indexes are dropped and only `user_uuid`-scoped indexes remain (`discussion_r2821839470`)
- [ ] Verify initial admin password is random, logged once, and no insecure static default remains (`discussion_r2821847803`)
- [ ] Verify `change-password` CLI command exists and works (`discussion_r2821847803`)
- [ ] Verify `userUuid` is mandatory/non-null across schema, models, DB methods, and callers (`discussion_r2821852449`, `discussion_r2821855624`, `discussion_r2821857516`, `discussion_r2821859256`, `discussion_r2821860580`, `discussion_r2821863118`, `discussion_r2821867526`, `discussion_r2821868460`)
- [ ] Verify settings parse failures in `UsersDatabase` log at `error` level (not `warn`) (`discussion_r2821871984`)
- [ ] Verify `UserSettings` uses config types directly and does not duplicate mirror `*UserSettings` config wrappers (`discussion_r2821906798`)
- [ ] Verify `UserSettings` location/organization aligns with requested module boundaries and current conventions (`discussion_r2822180011`)
- [ ] Verify token helpers are consolidated into a single payload method (e.g. `verifyTokenPayload`) returning username + user UUID (`discussion_r2821913018`)
- [ ] Verify login unknown-user path returns `403 Forbidden` and not `500` (`discussion_r2821922702`)
- [ ] Verify login response exposes `configuredServices` semantics only (no stale `hasAuth`/`AuthStatus`) (`discussion_r2822151945`)
- [ ] Verify route auth extraction is pure and route dependencies are decoupled (`SettingsRoutes` not depending on `AuthRoutes`) (`discussion_r2822163550`, `discussion_r2822225532`, `discussion_r2822230154`)
- [ ] Verify reusable response helpers are used from `server/utils.kt` (`discussion_r2822214254`)
- [ ] Verify LLM endpoint logic is fully extracted out of `Server.kt` into `LlmRoutes.kt` (`discussion_r2822239191`)
- [ ] Verify per-request user context loading avoids repeated DB lookups in route handlers (`discussion_r2822256611`, `discussion_r2822262583`, `discussion_r2822268205`)
- [ ] Verify service modules are startup-singletons and not reconstructed per-request in route handlers (`discussion_r2822279207`, `discussion_r2822291883`, `discussion_r2822295718`)
- [ ] Verify `PublishRoutes` signature and internals consistently use provided dependencies and no dead parameters remain (`discussion_r2822169489`)

## Remaining Future Work (Beyond Current PR Scope)

- Refresh-token rotation and stronger per-user session lifecycle policies
- Admin UI for user management (create/edit/disable users)
