# Threaded Posts Backend API Plan

## Scope

Implement backend API support for publishing message threads (multiple messages linked by replies) across Mastodon, Bluesky, X/Twitter, and LinkedIn-with-restrictions, while updating the frontend API contract usage and feed link wiring (no thread-composer UI redesign).

- In scope:
  - Replace old single-message request contract with `messages: List<NewPostRequestMessage>`.
  - Add backend validation to prevent partial success caused by platform-specific bad requests.
  - Implement thread publishing semantics using post IDs/reply IDs.
  - Switch syndication output from RSS to Atom and model thread relationships via Atom Threading Extensions.
  - Define feed behavior for multi-message requests so API behavior stays consistent when `feed` is selected.
  - Support LinkedIn only as:
    - one primary post, plus
    - at most one follow-up as a comment.
  - Remove `cleanupHtml` feature from codebase.
  - Keep frontend compatible with the new backend API contract.
  - Add/fix frontend links so the per-user feed URL is always accessible from navbar and relevant screens.
- Out of scope:
  - New frontend UX for composing threads.
  - LinkedIn multi-comment threading behavior beyond one follow-up comment.

## Proposed HTTP API Contract

`POST /posts`

Request body (new, not backward compatible):

```kotlin
@Serializable
data class NewPostRequest(
    val targets: List<String>? = null,
    val language: String? = null,
    val messages: List<NewPostRequestMessage>,
)

@Serializable
data class NewPostRequestMessage(
    val content: String,
    val link: String? = null,
    val images: List<String>? = null,
)
```

Behavior proposal:

- `messages[0]` is the root post on each selected target.
- For Mastodon, Bluesky, X/Twitter:
  - `messages[1..n]` are published as replies linked to the previous post (`replyToId` chaining).
- For LinkedIn:
  - Only `messages.size <= 2` allowed.
  - `messages[0]` = normal LinkedIn post.
  - `messages[1]` (if present) = comment on the root post.
- For feed (formerly `rss` target):
  - Allow any number of messages.
  - Persist one Atom entry per message in request order.
  - Use Atom `thr:in-reply-to` (`RFC 4685`, section 3) for entries after the root entry.
  - Include each feed entry ID in the same per-message result structure used for other targets.

Validation proposal (pre-publish, global):

- Reject request before any external API call if any selected target would fail by contract.
- Reject LinkedIn + `messages.size > 2`.
- Reject LinkedIn follow-up constraints violations:
  - follow-up content length over LinkedIn comment limit (use configured constant; verify exact value during implementation),
  - more than one image in follow-up,
  - follow-up image with alt text requirement mismatch (if comment image alt text unsupported, enforce no alt text dependent flow).
- Reject empty `messages`.
- Keep existing per-platform content/media validations and adapt them to per-message validation.

Response proposal:

- Keep current response shape where possible, but include per-message published IDs per target so thread links are explicit and auditable.
- Ensure internal publish result model carries:
  - root post ID per platform,
  - follow-up post/comment IDs in order,
  - linkage metadata (`replyTo` / `commentOn`) for debug visibility.

## Implementation Plan (Checklist)

Progress tracking rule: update this checklist in real time during implementation (mark `[x]` immediately when done).

### 1) API Contract + Serialization

- [ ] Replace `NewPostRequest` model with `messages: List<NewPostRequestMessage>`.
- [ ] Add `NewPostRequestMessage` serialized model.
- [ ] Remove old single-message request fields/usages from backend request parsing.
- [ ] Update endpoint tests to fail with old payload and pass with new payload.

### 2) Validation Layer (Pre-flight)

- [ ] Introduce request-level thread validation before spawning parallel target publishes.
- [ ] Add generic validations (`messages` non-empty, message content basic constraints).
- [ ] Add LinkedIn thread restrictions validation (`messages.size <= 2` when LinkedIn selected).
- [ ] Add LinkedIn follow-up comment validations (length, single image, comment-media constraints).
- [ ] Return clear 400 error payloads with actionable validation messages.
- [ ] Add/expand validation unit tests, including mixed-target scenarios.

### 3) Publishing Domain Model + Thread Execution

- [ ] Refactor internal publish command/model from single message to ordered message list.
- [ ] Add platform-agnostic thread publication abstraction using prior post IDs.
- [ ] Ensure per-platform publish implementations can consume `replyTo` or equivalent parent reference.
- [ ] Confirm thread order guarantees per platform implementation (sequential per target, still parallel across targets).
- [ ] Add tests that verify no publish starts when pre-flight validation fails.

### 4) Platform Implementations

- [ ] Mastodon: implement chained reply posting for follow-up messages.
- [ ] Bluesky: implement chained reply posting for follow-up messages.
- [ ] X/Twitter: implement chained reply posting for follow-up messages.
- [ ] LinkedIn: implement optional single follow-up as comment on root post.
- [ ] LinkedIn: enforce/confirm comment media and alt-text handling policy.
- [ ] Feed: migrate syndication generation from RSS to Atom.
- [ ] Feed: emit `thr:in-reply-to` for non-root thread entries.
- [ ] Feed: use Rome support for Atom threading extension where available.
- [ ] Feed: map thread messages to ordered Atom entries (one entry per message).
- [ ] Add platform-focused tests/mocks for thread ID propagation and ordering.

### 5) Response + ID Tracking

- [ ] Extend backend response/domain DTOs to include per-message IDs per target.
- [ ] Ensure linkage metadata is preserved (reply/comment parent references).
- [ ] Update API tests/assertions for new response details.
- [ ] Update response/link descriptors and labels to use `feed` naming (not `rss`).

### 6) Remove `cleanupHtml`

- [ ] Locate all `cleanupHtml` references in backend/frontend shared flow.
- [ ] Remove implementation and related invocations.
- [ ] Remove or update tests tied to `cleanupHtml` behavior.
- [ ] Verify formatting/sanitization behavior still matches current product expectations.

### 7) Frontend Compatibility (No UI Expansion)

- [ ] Update frontend request builder/types to send `messages` array.
- [ ] Adapt frontend API client serialization/deserialization to new contract.
- [ ] Keep existing UI behavior by mapping current single-message input into one-item `messages`.
- [ ] Add a working per-user feed link in navbar.
- [ ] Fix existing frontend feed links/routes/descriptions still using `rss` naming.
- [ ] Update frontend tests for API contract compatibility.

### 8) Regression Safety + Docs

- [ ] Add integration tests for:
  - multi-message thread (Mastodon/Bluesky/X),
  - LinkedIn root + one comment,
  - multi-message thread with `feed` selected produces ordered Atom entries,
  - Atom output includes `thr:in-reply-to` for follow-up entries,
  - LinkedIn with >2 messages rejected pre-flight,
  - mixed targets invalid because of LinkedIn constraints rejected pre-flight.
- [ ] Run `./gradlew :backend:test :frontend:test` and fix regressions.
- [ ] Run formatting (`make format`) and ensure clean lint/test status.
- [ ] Document final API examples in `test.http` and/or backend docs.
- [ ] Update docs/endpoints/descriptions to consistently say `feed` instead of `rss`.

## Open Decisions to Confirm During Implementation

- Exact LinkedIn comment max length constant (set conservatively, confirm from API docs/observed constraints).
- Final response schema for per-message IDs (extend existing response vs introduce a nested `thread` section).
- How to represent unsupported per-comment alt-text cleanly in validation error messaging.
- Whether to keep temporary backward-compatible `/rss/*` aliases/redirects or fully replace with `/feed/*` in one release.
