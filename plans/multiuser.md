# Multi-User Support Plan

## Overview

Transform the app from a single-user (env-var-based config) system to a multi-user system
where each user owns their data and manages their own social network credentials.

## Status

- [x] Plan created
- [x] Phase 1: DB schema – user_uuid in all tables + admin user migration
- [x] Phase 2: UserSettings model + settings column in users table
- [x] Phase 3: Auth switch from env-var to database
- [x] Phase 4: Per-request module creation from user settings
- [x] Phase 5: Settings API endpoints (GET/PUT /api/account/settings)
- [x] Phase 6: Frontend – settings form in /account
- [x] Phase 7: Frontend – conditional service checkboxes in publish form

## Architecture

### Data Ownership

Every document/post/upload row has a `user_uuid` column (nullable for backward compatibility).
Queries are filtered by `user_uuid` when the user context is known.

### User Settings

Stored as a JSON blob in `users.settings`. Structure:

```json
{
  "bluesky":   { "service": "...", "username": "...", "password": "..." },
  "mastodon":  { "host": "...", "accessToken": "..." },
  "twitter":   { "oauth1ConsumerKey": "...", "oauth1ConsumerSecret": "..." },
  "linkedin":  { "clientId": "...", "clientSecret": "..." },
  "llm":       { "apiUrl": "...", "apiKey": "...", "model": "..." }
}
```

### Authentication

- Removed: `SERVER_AUTH_USERNAME`, `SERVER_AUTH_PASSWORD` env vars
- Now: username/password verified against `users` table
- JWT carries: `username` + `userUuid`
- On request: JWT → userUuid → user settings → build modules

### Per-Request Module Creation

Social network modules (Bluesky, Mastodon, Twitter, LinkedIn, LLM) are now created
per-request inside `resourceScope`, using credentials from the authenticated user's settings.

## Migrations

| # | Description |
|---|-------------|
| 0 | documents table |
| 1 | document_tags table |
| 2 | uploads table |
| 3 | users table |
| 4 | user_sessions table |
| 5 | admin user creation (password: "changeme") |
| 6 | settings column in users table |
| 7 | user_uuid in documents, document_tags, uploads; update existing rows to admin user |

## Files Changed

### Backend
- `db/migrations.kt` – migrations 5, 6, 7
- `db/models.kt` – User.settings, Document.userUuid
- `db/UserSettings.kt` – new; UserSettings data class hierarchy
- `db/UsersDatabase.kt` – settings in CRUD, findByUuid, updateSettings
- `db/DocumentsDatabase.kt` – userUuid parameter
- `db/FilesDatabase.kt` – userUuid parameter
- `db/PostsDatabase.kt` – userUuid threading
- `modules/AuthModule.kt` – JWT carries userUuid
- `server/ServerConfig.kt` – remove username/passwordHash from ServerAuthConfig
- `server/routes/AuthRoutes.kt` – DB-based auth, new JWT payload
- `server/routes/SettingsRoutes.kt` – new; GET/PUT /api/account/settings
- `server/Server.kt` – per-request modules, pass usersDb
- `AppConfig.kt` – remove social network configs
- `Main.kt` – remove SERVER_AUTH_USERNAME/PASSWORD and social network CLI opts

### Frontend
- `models/Auth.kt` – UserSettings in LoginResponse
- `models/UserSettings.kt` – new; UserSettings for frontend
- `utils/Storage.kt` – store user settings
- `pages/AccountPage.kt` – settings form
- `pages/PublishFormPage.kt` – conditional service checkboxes

## Unresolved / Future Work

- Twitter/LinkedIn OAuth state keyed by user (currently single-user assumption in "twitter-oauth-token" key)
- Refresh token rotation and per-user session management
- Admin UI for managing multiple users
