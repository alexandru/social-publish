# Code Review Fixes Plan

## Overview

Five issues were identified during the code review of the threaded posts backend API branch. This plan covers all of them in priority order. Two are bugs that should be fixed before the branch is merged; two are design issues worth addressing; one is a minor consistency gap.

---

## Issue 1 — `RssModule.createPosts()`: Uncaught DB exception crashes broadcast

**File:** `backend/src/main/kotlin/socialpublish/backend/modules/RssModule.kt:85`

**Problem:**

```kotlin
val post = postsDb.create(payload, normalizedTargets, userUuid).getOrElse { throw it }
```

`postsDb.create()` returns `Either<DBException, Post>`. The `.getOrElse { throw it }` re-throws the `DBException` as an uncaught exception. `createPosts()` has no `try/catch` of its own (unlike `createPost()` which wraps its call to `createPosts()` in a try/catch).

When `PublishModule.broadcastPost()` calls `rssModule.createPosts()` directly (line 79–85 of `PublishModule.kt`), it does so inside an `async {}` block. An uncaught exception escaping an `async` coroutine will propagate to the parent `coroutineScope` and cancel *all* other in-flight tasks — Mastodon, Bluesky, Twitter, LinkedIn — before they complete. The entire broadcast fails structurally rather than returning a per-target error. Other platform modules (`mastodonModule.createThread`, etc.) all return `ApiResult` and never throw, so only the RSS/feed path has this gap.

**Fix:**

Replace the throwing `.getOrElse` with a proper `Either`-based error return. The `createPosts()` function should return `ApiResult<NewPostResponse>` (it already does) and use Arrow's `either {}` builder or map the `DBException` to a `CaughtException.left()` inline.

Concretely, change `createPosts()` to propagate DB errors as typed `Left` values instead of throwing:

```kotlin
// Before
val post = postsDb.create(payload, normalizedTargets, userUuid).getOrElse { throw it }

// After (within an either {} block, or inline with when)
val post = when (val result = postsDb.create(payload, normalizedTargets, userUuid)) {
    is Either.Right -> result.value
    is Either.Left -> {
        logger.error(result.value) { "Failed to save feed item" }
        return CaughtException(
            status = 500,
            module = "feed",
            errorMessage = "Failed to save feed item: ${result.value.message}",
        ).left()
    }
}
```

Since `createPosts()` is a loop, a clean alternative is to restructure it with an `either {}` block from Arrow so that `bind()` short-circuits on error without throwing. Either approach removes the throw.

**Tests to add/update:**

- Unit test for `RssModule.createPosts()` where `postsDb.create()` returns a `Left(DBException(...))` — assert the result is a `Left<CaughtException>`, not an uncaught exception.
- Confirm the existing `createPost()` test coverage still passes (it wraps `createPosts()` in try/catch, but the inner fix should still be correct).

---

## Issue 2 — `LinkedInApiModule.createComment()`: Incomplete JSON escaping

**File:** `backend/src/main/kotlin/socialpublish/backend/clients/linkedin/LinkedInApiModule.kt:1030–1044`

**Problem:**

The request body for LinkedIn comments is assembled via string interpolation with only `"` escaped:

```kotlin
append(message.content.replace("\"", "\\\""))
```

This is insufficient. If `message.content` contains any of the following characters, the resulting string is not valid JSON and will cause a 400 from LinkedIn's API (or silently corrupt the payload):

| Character | Unescaped produces |
|-----------|-------------------|
| `\` | invalid escape sequence |
| newline `\n` | bare newline inside a JSON string literal |
| carriage return `\r` | same |
| tab `\t` | bare tab inside a JSON string literal |

The `personUrn` and `rootPostId` values come from LinkedIn's own API, so they are unlikely to contain special characters — but `message.content` is user-supplied and can contain any of the above.

**Fix:**

Replace the manual `buildString` body construction with proper serialization. The simplest approach is to build a `@Serializable` data class for the comment request (consistent with the rest of the module, which already uses Kotlin Serialization for all other request bodies) and serialize it via `jsonConfig.encodeToString(...)`.

Define the data class (can be file-private or in a companion):

```kotlin
@Serializable
private data class LinkedInCommentRequest(
    val actor: String,
    val `object`: String,
    val message: LinkedInCommentMessage,
    val content: List<LinkedInCommentContent>? = null,
)

@Serializable
private data class LinkedInCommentMessage(val text: String)

@Serializable
private data class LinkedInCommentContent(val entity: String)
```

Then replace the `buildString` block with:

```kotlin
val commentRequest = LinkedInCommentRequest(
    actor = personUrn,
    `object` = rootPostId,
    message = LinkedInCommentMessage(text = message.content),
    content = uploadedAsset?.let { listOf(LinkedInCommentContent(entity = it.asset)) },
)
val requestBody = jsonConfig.encodeToString(LinkedInCommentRequest.serializer(), commentRequest)
```

`jsonConfig` already has `explicitNulls = false`, so a null `content` field will be omitted — matching the existing behavior of only including `content` when an asset is present.

**Tests to add/update:**

- Unit test for `createComment()` where `message.content` contains `\`, `\n`, `\r`, `\t`, and `"` — assert the HTTP request body sent is valid JSON (can be verified by parsing it with `Json.parseToJsonElement` in the test).
- Existing happy-path test for `createThread()` should still pass.

---

## Issue 3 — `RssModule.generateFeed()`: Thread replies appear before root post in Atom feed

**File:** `backend/src/main/kotlin/socialpublish/backend/modules/RssModule.kt:106` / `backend/src/main/kotlin/socialpublish/backend/db/PostsDatabase.kt:41`

**Problem:**

`postsDb.getAllForUser()` uses `DocumentsDatabase.OrderBy.CREATED_AT_DESC`, so posts are returned newest-first. For a thread created in one request, reply entries are inserted after the root (they have a higher `createdAt`), so they appear *before* the root in the feed. Feed readers that process entries sequentially (e.g., to build a conversation view) will encounter replies before the post they reference via `thr:in-reply-to`.

The Atom Threading Extensions spec (RFC 4685) does not mandate entry ordering, but the intent of the `thr:in-reply-to` relationship is that the referenced entry is already known to the reader. Conventional feed readers and aggregators generally process entries top-to-bottom.

**Fix:**

Change the ordering for feed generation to ascending (oldest first) so thread root entries precede their replies. There are two approaches:

**Option A (preferred):** Reverse the `posts` list after fetching in `generateFeed()`. This is a one-line change that is local to feed generation and does not affect anything else that calls `getAllForUser()`.

```kotlin
// In generateFeed(), after:
val posts = postsDb.getAllForUser(userUuid).getOrElse { throw it }
// Add:
val orderedPosts = posts.reversed()
// Then iterate over orderedPosts instead of posts
```

**Option B:** Add a separate `getAllForUserAsc` query to `PostsDatabase` / `DocumentsDatabase`. More invasive for a simple ordering change; prefer Option A unless ascending order becomes needed elsewhere.

**Tests to add/update:**

- Unit/integration test for `generateFeed()` with a two-message thread: assert the root entry appears before the reply entry in the output XML.
- Verify the `thr:in-reply-to` `ref` attribute on the reply entry matches the root entry's `uri`.

---

## Issue 4 — `LinkedInApiModule.validateThreadRequest()`: File I/O side effect during validation

**File:** `backend/src/main/kotlin/socialpublish/backend/clients/linkedin/LinkedInApiModule.kt:195–211`

**Problem:**

`validateThreadRequest()` calls `filesModule.readImageFile(imageUuid, userUuid)` — a file system read — as part of pre-flight validation. This violates the function's implied contract ("validate the request without side effects") in two ways:

1. **Misleading name**: callers (`PublishModule.broadcastPost()`, `createThread()`) expect validation to be side-effect-free. A file read is an I/O operation with latency and failure modes.
2. **Wasted work**: if validation passes but the actual publish later fails for an unrelated reason (e.g., LinkedIn API error on the root post), the file was read for nothing. When `createComment()` runs, `uploadMedia()` will read the same file again.

The file read in `validateThreadRequest()` exists to check two things:
- The file exists for the given user (returns `null` if not).
- The file has no alt text (LinkedIn comments do not support image alt text).

**Fix:**

Move the file-existence and alt-text checks into `createComment()` where the file is already read by `uploadMedia()`. `uploadMedia()` already returns a `ValidationError` (404) if the file is not found. The alt-text check can be done there too, before or after the upload.

Concretely:

1. Remove the `for (imageUuid in followUp.images.orEmpty()) { filesModule.readImageFile(...) }` block from `validateThreadRequest()`.
2. In `createComment()`, after `uploadMedia()` resolves and returns a successful `UploadedAsset`, check whether the original file had alt text using the already-available `uploadedAsset.description` field (which is populated from `file.altText` in `uploadMedia()`). If non-null/non-blank, return a `ValidationError`.

This consolidates the file read to one place (`uploadMedia()`) and keeps `validateThreadRequest()` a pure structural/length check.

**Tests to add/update:**

- Update existing `validateThreadRequest()` tests to confirm they no longer perform file I/O (mock `filesModule` should not be called).
- Add a test for `createComment()` where the uploaded image has non-blank alt text — assert a `ValidationError` is returned.

---

## Issue 5 — `Server.kt`: Alias route `/api/rss/post` missing `.describe {}`

**File:** `backend/src/main/kotlin/socialpublish/backend/server/Server.kt:429–432`

**Problem:**

The backward-compatibility alias route has no `.describe {}` block:

```kotlin
post("/api/rss/post") {
    val userUuid = call.requireUserUuid() ?: return@post
    rssRoutes.createPostRoute(userUuid, call)
}
```

Every other documented endpoint in `Server.kt` has a `.describe {}` block. If OpenAPI/Swagger documentation is generated from route descriptions, this endpoint will be invisible in the generated spec.

**Fix:**

We will not add a deprecated alias. The project decision is to replace `/api/rss` with `/api/feed` and *remove* the `/api/rss/post` alias entirely provided the frontend and client consumers have already been updated.

Concretely:

- Remove the `post("/api/rss/post") { ... }` block from `Server.kt`.
- Update any server-side references or helpers that use `rss` naming to use `feed` consistently (routes, responses, docs).
- Update `test.http`, integration tests, and any other artifacts to use `/api/feed/*`.

**Tests to add/update:**

- Ensure all backend tests reference `/api/feed` (the current test suite already uses `/api/feed/post`).
- Add a checklist item to run a repository-wide search for `/api/rss` and fail the CI step if any occurrences remain (pre-merge safety check).

**Coordination / rollout note:**

- This change assumes the frontend has already been updated to use `/api/feed` — confirm this before merging. If there are external integrators, communicate the breaking change and provide a migration window.


---

## Summary

| # | Priority | File | Change |
|---|----------|------|--------|
| 1 | High (bug) | `RssModule.kt:85` | Replace `getOrElse { throw it }` with typed `Left` return |
| 2 | High (bug) | `LinkedInApiModule.kt:1030` | Replace manual JSON string with `@Serializable` data class + `jsonConfig.encodeToString` |
| 3 | Medium | `RssModule.kt:106` + `PostsDatabase.kt:41` | Reverse post order in `generateFeed()` so root precedes replies |
| 4 | Medium | `LinkedInApiModule.kt:195` | Remove file I/O from `validateThreadRequest()`; move alt-text check to `createComment()` |
| 5 | Low | `Server.kt:429` | Add `.describe {}` to `/api/rss/post` alias route |

Issues 1 and 2 are bugs and should be fixed first. Issues 3 and 4 can be done together since both touch `LinkedInApiModule` and `RssModule`. Issue 5 is a one-liner and can be bundled with any of the above.
