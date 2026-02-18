# Multiuser Security Follow-Up Plan

## Goal

Close the remaining multiuser security gaps from PR #143 by enforcing strict user binding across auth, OAuth flows, and DB lookups.

## Required fixes

1. OAuth account-binding hardening
   - Stop trusting request-provided `access_token` in Twitter/LinkedIn authorize routes.
   - Build callback token from the authenticated principal identity only.
   - Keep callback/account linkage tied to the same authenticated user.

2. JWT validation hardening
   - Require both `username` and `userUuid` claims in `auth-jwt` validation.
   - Treat tokens missing `userUuid` as unauthorized.

3. User-scoped document lookups
   - Replace `DocumentsDatabase.searchByKey(searchKey: String)` with a user-bound variant.
   - Update all call sites to pass `userUuid`.
   - Ensure LinkedIn OAuth state verification is user-bound.

4. RSS route consistency
   - Fix generated RSS item GUID/link to use `/rss/{userUuid}/{uuid}`.

## Tests (TDD)

1. Add/adjust tests so they fail first:
   - Auth rejects JWTs without `userUuid` claim.
   - Documents lookup by search key is user-scoped.
   - OAuth authorize route no longer accepts request token override behavior.
   - RSS feed item link format includes user UUID.

2. Implement fixes and make tests pass.

3. Run targeted backend tests, then broader backend test suite if needed.

## Notes from spec alignment

- Treat all persisted records as user-owned data.
- Enforce user UUID binding for all DB reads in authenticated user flows.
- `documents.user_uuid` and `uploads.user_uuid` remain `NOT NULL` and required in app logic.
