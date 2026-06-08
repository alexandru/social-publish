# Specification: Recreate Threaded Post Publishing

## 1. Purpose and Scope

This document specifies the threaded-posts feature as a standalone implementation contract.

The feature changes Social Publish from “publish one post” to “publish one ordered non-empty list of messages as a thread” across these targets:

- `feed`
- `mastodon`
- `bluesky`
- `twitter`
- `linkedin`

### In scope

- Backend HTTP contract for thread-capable post creation.
- Exact request/response DTOs and OpenAPI schemas.
- Feed persistence and Atom thread metadata.
- Thread-aware high-level service interfaces.
- Platform-specific API request/response details.
- Preflight validation rules.
- Frontend compatibility DTOs and behavior.

### Out of scope

- Line-for-line preservation of any existing code.
- A new frontend UI for composing multi-message threads.
- LinkedIn arbitrary-depth threads. LinkedIn supports only one root post plus one optional first-level comment in this feature.

## 2. Core Thread Semantics

For every selected target, the request is interpreted as an ordered thread:

```text
messages[0] = root message
messages[1] = first follow-up
messages[2] = second follow-up
...
```

Target behavior:

| Target | Root behavior | Follow-up behavior |
| --- | --- | --- |
| `feed` | Persist as a feed entry. | Persist as a feed entry whose parent is the immediately previous feed entry. |
| `mastodon` | Create status. | Create status with `in_reply_to_id` set to previous status ID. |
| `bluesky` | Create `app.bsky.feed.post` record. | Create `app.bsky.feed.post` record with `reply.root` set to root strong ref and `reply.parent` set to previous strong ref. |
| `twitter` | Create post/tweet. | Create post/tweet with `reply.in_reply_to_tweet_id` set to previous post ID. |
| `linkedin` | Create normal LinkedIn post. | Create exactly one first-level comment on the root post. More follow-ups are rejected. |

Validation is preflight: validate the entire request for every selected target before any external API call or feed persistence. If validation fails, there must be no publishing side effects.

## 3. Backend API Contract

### 3.1 Endpoints

Thread-capable publishing is available on these endpoints:

| Endpoint | Purpose | Success response |
| --- | --- | --- |
| `POST /api/feed/post` | Publish only to feed. | `NewFeedPostResponse` |
| `POST /api/bluesky/post` | Publish only to Bluesky. | `NewBlueSkyPostResponse` |
| `POST /api/mastodon/post` | Publish only to Mastodon. | `NewMastodonPostResponse` |
| `POST /api/twitter/post` | Publish only to Twitter/X. | `NewTwitterPostResponse` |
| `POST /api/linkedin/post` | Publish only to LinkedIn. | `NewLinkedInPostResponse` |
| `POST /api/multiple/post` | Publish to `request.targets`. | `Map<String, NewPostResponse>` keyed by target name |

Target names are lowercase. Use `feed`, not `rss`, in public API payloads.

`targets` behavior:

- Route-specific endpoints (`/api/feed/post`, `/api/mastodon/post`, etc.) publish to that route's target even if `request.targets` is absent; route handlers may set or override `targets` to the route target before calling the module.
- `/api/multiple/post` publishes exactly the normalized target set in `request.targets`.
- For `/api/multiple/post`, missing or empty `targets` means no selected external/feed target. Return `400 { "error": "No publish targets selected" }`; do not return a successful empty result.
- Unknown target names are validation errors.

### 3.2 Backend request DTOs

```kotlin
@file:UseSerializers(NonEmptyListSerializer::class)

import arrow.core.NonEmptyList
import arrow.core.serialization.NonEmptyListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class NewPostRequest(
    val targets: List<String>? = null,
    val language: String? = null,
    val messages: NonEmptyList<NewPostRequestMessage>,
)

@Serializable
data class NewPostRequestMessage(
    val content: String,
    val link: String? = null,
    val images: List<String>? = null,
)
```

Compatibility requirement: legacy JSON/form submissions with a single `content`, `link`, `images`, and `language` may be accepted by route parsing, but they must be normalized immediately to `messages = nonEmptyListOf(NewPostRequestMessage(...))`. Do not maintain separate publishing paths for legacy and thread payloads.

Legacy form normalization contract:

```text
content=<string>             -> messages[0].content
link=<string?>               -> messages[0].link
images=<repeated/string?>    -> messages[0].images
language=<string?>           -> request.language
targets=<repeated/string?>   -> request.targets
```

### 3.3 Backend response DTOs

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class PublishedMessageResponse(
    val id: String,
    val uri: String? = null,
    val replyToId: String? = null,
    val commentOnId: String? = null,
)

@Serializable
sealed class NewPostResponse {
    abstract val module: String
}

@Serializable
data class NewBlueSkyPostResponse(
    val uri: String,
    val cid: String? = null,
    val messages: List<PublishedMessageResponse> = emptyList(),
) : NewPostResponse() {
    override val module: String = "bluesky"
}

@Serializable
data class NewMastodonPostResponse(
    val uri: String,
    val id: String = "",
    val messages: List<PublishedMessageResponse> = emptyList(),
) : NewPostResponse() {
    override val module: String = "mastodon"
}

@Serializable
data class NewFeedPostResponse(
    val uri: String,
    val messages: List<PublishedMessageResponse> = emptyList(),
) : NewPostResponse() {
    override val module: String = "feed"
}

@Serializable
data class NewTwitterPostResponse(
    val id: String,
    val messages: List<PublishedMessageResponse> = emptyList(),
) : NewPostResponse() {
    override val module: String = "twitter"
}

@Serializable
data class NewLinkedInPostResponse(
    val postId: String,
    val messages: List<PublishedMessageResponse> = emptyList(),
) : NewPostResponse() {
    override val module: String = "linkedin"
}
```

Response invariants:

- `messages` is ordered exactly like the request messages that were published for that target.
- For `mastodon`, `twitter`, and `bluesky`, root `messages[0].replyToId == null`; every follow-up has `replyToId` set to the canonical parent identifier used by that target's reply API (`status id` for Mastodon, post/tweet id for Twitter/X, parent AT URI for Bluesky).
- For `linkedin`, root `messages[0].commentOnId == null`; the optional comment has `commentOnId` set to the root LinkedIn post ID/URN.
- For `feed`, follow-up `replyToId` identifies the parent stored feed post and must match the Atom `thr:in-reply-to` relationship.

### 3.4 Error DTOs and HTTP error mapping

Internal modules may use typed errors, but HTTP clients should see the route-level shapes below.

```kotlin
@Serializable
sealed class ApiError {
    abstract val status: Int
    abstract val module: String?
    abstract val errorMessage: String
}

@Serializable
@SerialName("validation")
data class ValidationError(
    override val status: Int,
    override val errorMessage: String,
    override val module: String? = null,
) : ApiError()

@Serializable
data class ErrorResponse(
    val error: String,
)

@Serializable
data class CompositeErrorResponse(
    val type: String, // "success" or "error"
    val module: String? = null,
    val status: Int? = null,
    val error: String? = null,
    val result: NewPostResponse? = null,
)

@Serializable
data class CompositeErrorWithDetails(
    val error: String,
    val responses: List<CompositeErrorResponse>,
)
```

HTTP route behavior:

- Preflight validation failures return `ErrorResponse(error = validation.errorMessage)` with the validation status, usually HTTP 400, and no side effects.
- Single-target route failures return `ErrorResponse(error = apiError.errorMessage)` using the internal error status.
- `/api/multiple/post` returns `Map<String, NewPostResponse>` on full success.
- If publishing begins and one or more selected targets fail, `/api/multiple/post` returns `CompositeErrorWithDetails(error = composite.errorMessage, responses = composite.responses)` with the composite status. Each response entry is either `{ "type": "success", "result": ... }` or `{ "type": "error", "module": "...", "error": "..." }`.

### 3.5 OpenAPI schema

```yaml
openapi: 3.1.0
info:
  title: Social Publish Threaded Posting API
  version: threaded-posts-recreation
paths:
  /api/feed/post:
    post:
      operationId: createFeedThread
      requestBody:
        $ref: '#/components/requestBodies/NewPostRequest'
      responses:
        '200': { $ref: '#/components/responses/NewFeedPostResponse' }
        '400': { $ref: '#/components/responses/ErrorResponse' }
  /api/bluesky/post:
    post:
      operationId: createBlueskyThread
      requestBody:
        $ref: '#/components/requestBodies/NewPostRequest'
      responses:
        '200': { $ref: '#/components/responses/NewBlueSkyPostResponse' }
        '400': { $ref: '#/components/responses/ErrorResponse' }
  /api/mastodon/post:
    post:
      operationId: createMastodonThread
      requestBody:
        $ref: '#/components/requestBodies/NewPostRequest'
      responses:
        '200': { $ref: '#/components/responses/NewMastodonPostResponse' }
        '400': { $ref: '#/components/responses/ErrorResponse' }
  /api/twitter/post:
    post:
      operationId: createTwitterThread
      requestBody:
        $ref: '#/components/requestBodies/NewPostRequest'
      responses:
        '200': { $ref: '#/components/responses/NewTwitterPostResponse' }
        '400': { $ref: '#/components/responses/ErrorResponse' }
  /api/linkedin/post:
    post:
      operationId: createLinkedInRootPostAndOptionalComment
      requestBody:
        $ref: '#/components/requestBodies/NewPostRequest'
      responses:
        '200': { $ref: '#/components/responses/NewLinkedInPostResponse' }
        '400': { $ref: '#/components/responses/ErrorResponse' }
  /api/multiple/post:
    post:
      operationId: publishToSelectedTargets
      requestBody:
        $ref: '#/components/requestBodies/NewPostRequest'
      responses:
        '200':
          description: Full success. Object keys are selected target names.
          content:
            application/json:
              schema:
                type: object
                additionalProperties:
                  $ref: '#/components/schemas/NewPostResponse'
        '400': { $ref: '#/components/responses/ErrorResponse' }
        '502': { $ref: '#/components/responses/CompositeErrorWithDetails' }
components:
  requestBodies:
    NewPostRequest:
      required: true
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/NewPostRequest'
  responses:
    ErrorResponse:
      description: Request failed before successful response mapping.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    CompositeErrorWithDetails:
      description: One or more target publishes failed after preflight.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/CompositeErrorWithDetails'
    NewFeedPostResponse:
      description: Feed thread response.
      content:
        application/json:
          schema: { $ref: '#/components/schemas/NewFeedPostResponse' }
    NewBlueSkyPostResponse:
      description: Bluesky thread response.
      content:
        application/json:
          schema: { $ref: '#/components/schemas/NewBlueSkyPostResponse' }
    NewMastodonPostResponse:
      description: Mastodon thread response.
      content:
        application/json:
          schema: { $ref: '#/components/schemas/NewMastodonPostResponse' }
    NewTwitterPostResponse:
      description: Twitter/X thread response.
      content:
        application/json:
          schema: { $ref: '#/components/schemas/NewTwitterPostResponse' }
    NewLinkedInPostResponse:
      description: LinkedIn root post and optional comment response.
      content:
        application/json:
          schema: { $ref: '#/components/schemas/NewLinkedInPostResponse' }
  schemas:
    NewPostRequest:
      type: object
      additionalProperties: false
      required: [messages]
      properties:
        targets:
          type: array
          nullable: true
          items:
            type: string
            enum: [feed, bluesky, mastodon, twitter, linkedin]
        language:
          type: string
          nullable: true
        messages:
          type: array
          minItems: 1
          items:
            $ref: '#/components/schemas/NewPostRequestMessage'
    NewPostRequestMessage:
      type: object
      additionalProperties: false
      required: [content]
      properties:
        content:
          type: string
          minLength: 1
        link:
          type: string
          format: uri
          nullable: true
        images:
          type: array
          nullable: true
          items:
            type: string
    PublishedMessageResponse:
      type: object
      additionalProperties: false
      required: [id]
      properties:
        id: { type: string }
        uri: { type: string, nullable: true }
        replyToId: { type: string, nullable: true }
        commentOnId: { type: string, nullable: true }
    NewPostResponse:
      oneOf:
        - $ref: '#/components/schemas/NewFeedPostResponse'
        - $ref: '#/components/schemas/NewBlueSkyPostResponse'
        - $ref: '#/components/schemas/NewMastodonPostResponse'
        - $ref: '#/components/schemas/NewTwitterPostResponse'
        - $ref: '#/components/schemas/NewLinkedInPostResponse'
      discriminator:
        propertyName: module
    NewFeedPostResponse:
      type: object
      required: [module, uri, messages]
      properties:
        module: { const: feed }
        uri: { type: string }
        messages:
          type: array
          items: { $ref: '#/components/schemas/PublishedMessageResponse' }
    NewBlueSkyPostResponse:
      type: object
      required: [module, uri, messages]
      properties:
        module: { const: bluesky }
        uri: { type: string }
        cid: { type: string, nullable: true }
        messages:
          type: array
          items: { $ref: '#/components/schemas/PublishedMessageResponse' }
    NewMastodonPostResponse:
      type: object
      required: [module, uri, id, messages]
      properties:
        module: { const: mastodon }
        uri: { type: string }
        id: { type: string }
        messages:
          type: array
          items: { $ref: '#/components/schemas/PublishedMessageResponse' }
    NewTwitterPostResponse:
      type: object
      required: [module, id, messages]
      properties:
        module: { const: twitter }
        id: { type: string }
        messages:
          type: array
          items: { $ref: '#/components/schemas/PublishedMessageResponse' }
    NewLinkedInPostResponse:
      type: object
      required: [module, postId, messages]
      properties:
        module: { const: linkedin }
        postId: { type: string }
        messages:
          type: array
          items: { $ref: '#/components/schemas/PublishedMessageResponse' }
    ErrorResponse:
      type: object
      required: [error]
      properties:
        error: { type: string }
    CompositeErrorResponse:
      type: object
      required: [type]
      properties:
        type: { type: string, enum: [success, error] }
        module: { type: string, nullable: true }
        status: { type: integer, nullable: true }
        error: { type: string, nullable: true }
        result:
          nullable: true
          $ref: '#/components/schemas/NewPostResponse'
    CompositeErrorWithDetails:
      type: object
      required: [error, responses]
      properties:
        error: { type: string }
        responses:
          type: array
          items: { $ref: '#/components/schemas/CompositeErrorResponse' }
```

### 3.6 Example request and response

Request:

```json
{
  "targets": ["feed", "mastodon", "twitter"],
  "language": "en",
  "messages": [
    {
      "content": "Root message",
      "link": "https://example.com/root"
    },
    {
      "content": "Follow-up message",
      "link": "https://example.com/follow-up"
    }
  ]
}
```

Successful `/api/multiple/post` response:

```json
{
  "feed": {
    "module": "feed",
    "uri": "/feed/user-id",
    "messages": [
      { "id": "018f-root", "uri": "/feed/items/018f-root" },
      { "id": "018f-reply", "uri": "/feed/items/018f-reply", "replyToId": "018f-root" }
    ]
  },
  "mastodon": {
    "module": "mastodon",
    "uri": "https://mastodon.example/@me/110000000000000001",
    "id": "110000000000000001",
    "messages": [
      { "id": "110000000000000001", "uri": "https://mastodon.example/@me/110000000000000001" },
      { "id": "110000000000000002", "uri": "https://mastodon.example/@me/110000000000000002", "replyToId": "110000000000000001" }
    ]
  },
  "twitter": {
    "module": "twitter",
    "id": "1800000000000000001",
    "messages": [
      { "id": "1800000000000000001" },
      { "id": "1800000000000000002", "replyToId": "1800000000000000001" }
    ]
  }
}
```

## 4. Feed Data Model and Atom Output

### 4.1 Stored data classes

Thread linkage must be persisted on feed posts:

```kotlin
@Serializable
data class PostPayload(
    val content: String,
    val link: String? = null,
    val language: String? = null,
    val images: List<String>? = null,
    val targets: List<String>? = null,
    val replyToPostUuid: UUIDv7? = null,
)

data class Post(
    val uuid: UUIDv7,
    val userUuid: UUIDv7,
    val payload: PostPayload,
    val createdAt: Instant,
    val replyToPostUuid: UUIDv7? = payload.replyToPostUuid,
)
```

If the storage layer keeps payloads as JSON instead of adding a dedicated SQL column, `replyToPostUuid` still needs to round-trip through `PostPayload` and be available on `Post`.

Minimum persistence contract:

```text
posts table / persisted post record
  uuid: UUIDv7 primary key
  userUuid: UUIDv7, indexed
  payload: JSON containing PostPayload, including replyToPostUuid
  createdAt: timestamp
```

Migration requirement:

- Existing persisted posts without `replyToPostUuid` remain valid; deserialization must treat the missing JSON field as `null`.
- New thread follow-up posts must persist `replyToPostUuid` in the payload JSON or in an equivalent nullable parent field that is exposed through `Post.replyToPostUuid`.
- Feed generation must be able to resolve a follow-up's parent by UUID to build Atom `thr:in-reply-to` metadata.

### 4.2 Feed creation rules

For a feed publish request with `N` messages:

1. Create `N` feed posts.
2. `messages[0]` has `replyToPostUuid = null`.
3. For `i > 0`, `messages[i].replyToPostUuid = posts[i - 1].uuid`.
4. Return `NewFeedPostResponse.messages` in the same order as the request.

### 4.3 Atom output

Use Atom output for thread-aware feeds.

Required namespace:

```xml
xmlns:thr="http://purl.org/syndication/thread/1.0"
```

For a non-root feed entry, emit an Atom Threading Extensions element:

```xml
<thr:in-reply-to
  ref="tag:example.com,2026:post:018f-root"
  href="https://example.com/feed/items/018f-root"
  type="text/html" />
```

The `ref` and `href` must identify the parent feed entry corresponding to `replyToPostUuid`.

Atom ID and parent mapping contract:

```text
atomEntryId(post) = stable Atom <id> value generated for post.uuid
atomEntryHref(post) = absolute public URL for the feed item/post

for root:
  no thr:in-reply-to

for reply post:
  parent = post with uuid == post.replyToPostUuid
  entry.thr:in-reply-to.ref = atomEntryId(parent)
  entry.thr:in-reply-to.href = atomEntryHref(parent)
  entry.thr:in-reply-to.type = "text/html"
  PublishedMessageResponse.replyToId = parent.uuid.toString()
```

The concrete Atom `<id>` format can follow the existing feed ID format, but it must be stable across feed regenerations. The `thr:in-reply-to ref` must exactly equal the parent entry's Atom `<id>`.

Official documentation:

- RFC 4685, Atom Threading Extensions: https://www.rfc-editor.org/rfc/rfc4685

## 5. High-Level Interfaces and Orchestration

### 5.1 Social media client interface

```kotlin
interface SocialMediaApi<Config> {
    fun validateRequest(request: NewPostRequest): ValidationError?

    suspend fun createThread(
        config: Config,
        request: NewPostRequest,
        userUuid: UUIDv7,
    ): ApiResult<NewPostResponse>
}
```

Contract:

- `validateRequest` checks the full ordered message list for one target.
- `createThread` publishes one target's thread, sequentially inside that target.
- Target modules must not expose single-post creation as the high-level publishing API for this feature. Single-message publishing is represented as a thread of length one.

### 5.2 Publish orchestration algorithm

```text
function broadcastPost(request): ApiResult<Map<String, NewPostResponse>>
  targets = request.targets.orEmpty().map(lowercase)

  for target in targets:
    ensure module/config exists where needed
    run target.validateRequest(request)
    if validation fails:
      return ValidationError without side effects

  tasks = []
  if "feed" in targets:
    tasks += feed.createPosts(request.messages)
  if "mastodon" in targets:
    tasks += mastodon.createThread(...)
  if "bluesky" in targets:
    tasks += bluesky.createThread(...)
  if "twitter" in targets:
    tasks += twitter.createThread(...)
  if "linkedin" in targets:
    tasks += linkedin.createThread(...)

  run tasks concurrently across targets
  collect results

  if any result is error:
    return CompositeErrorWithDetails
  else:
    return map targetName -> NewPostResponse
```

Concurrency rule: targets may run concurrently after validation; messages inside one target must publish sequentially because each follow-up needs the previous created ID.

Layering rule: modules must not depend on Ktor `ApplicationCall`, request parsing, or HTTP response writing. HTTP concerns belong in routes.

## 6. Platform Implementations

## 6.1 Shared post text construction

For service payloads, the effective text for a message with a link should be consistent across targets:

```kotlin
fun buildPostText(message: NewPostRequestMessage): String =
    if (message.link.isNullOrBlank()) message.content else "${message.content}\n\n${message.link}"
```

Validation should count the link as a fixed link weight where specified, not as its literal URL length.

### 6.2 Twitter/X

Official documentation:

- Create post endpoint, request schema, response schema, OAuth scopes, and `reply.in_reply_to_tweet_id`: https://docs.x.com/x-api/posts/creation-of-a-post
- X documentation index for current API pages: https://docs.x.com/llms.txt

Endpoint:

```http
POST https://api.x.com/2/tweets
Content-Type: application/json
Authorization: OAuth/OAuth2 user token with write permissions
```

Root payload:

```json
{
  "text": "Root message\n\nhttps://example.com/root"
}
```

Follow-up payload:

```json
{
  "text": "Follow-up message\n\nhttps://example.com/follow-up",
  "reply": {
    "in_reply_to_tweet_id": "1800000000000000001"
  }
}
```

Response fields needed:

```json
{
  "data": {
    "id": "1800000000000000002",
    "text": "Follow-up message https://t.co/..."
  }
}
```

Thread algorithm:

1. Publish root without `reply`.
2. Save returned `data.id` as `previousId` and root response `id`.
3. For each follow-up, publish with `reply.in_reply_to_tweet_id = previousId`.
4. Save returned ID and set `PublishedMessageResponse.replyToId = previousId`.
5. Update `previousId` to the returned ID.

### 6.3 Mastodon

Official documentation:

- Create status endpoint, `status`, `media_ids[]`, `in_reply_to_id`, `language`, OAuth `write:statuses`: https://docs.joinmastodon.org/methods/statuses/#create
- Status entity fields including `id`, `uri`, `url`, `in_reply_to_id`: https://docs.joinmastodon.org/entities/Status/

Endpoint:

```http
POST {mastodonBaseUrl}/api/v1/statuses
Authorization: Bearer {userAccessToken}
Content-Type: multipart/form-data or application/x-www-form-urlencoded
```

Root form fields:

```text
status=Root message\n\nhttps://example.com/root
language=en
media_ids[]=...
```

Follow-up form fields:

```text
status=Follow-up message\n\nhttps://example.com/follow-up
in_reply_to_id=1100000000000000001
language=en
media_ids[]=...
```

Response fields needed:

```json
{
  "id": "1100000000000000002",
  "uri": "https://mastodon.example/users/me/statuses/1100000000000000002",
  "url": "https://mastodon.example/@me/1100000000000000002",
  "in_reply_to_id": "1100000000000000001"
}
```

Thread algorithm is the same previous-ID chain as Twitter/X, using `in_reply_to_id`.

### 6.4 Bluesky / AT Protocol

Official documentation:

- `com.atproto.repo.createRecord`: https://docs.bsky.app/docs/api/com-atproto-repo-create-record
- `app.bsky.feed.post` lexicon with `text`, `facets`, `reply`, `embed`, `langs`, and `replyRef`: https://raw.githubusercontent.com/bluesky-social/atproto/main/lexicons/app/bsky/feed/post.json
- `com.atproto.repo.strongRef` lexicon used by `reply.root` and `reply.parent`: https://raw.githubusercontent.com/bluesky-social/atproto/main/lexicons/com/atproto/repo/strongRef.json
- `com.atproto.server.createSession` authentication endpoint: https://docs.bsky.app/docs/api/com-atproto-server-create-session

Endpoint:

```http
POST https://bsky.social/xrpc/com.atproto.repo.createRecord
Authorization: Bearer {accessJwt}
Content-Type: application/json
```

Root payload:

```json
{
  "repo": "did:plc:example",
  "collection": "app.bsky.feed.post",
  "record": {
    "$type": "app.bsky.feed.post",
    "text": "Root message",
    "createdAt": "2026-06-07T12:00:00.000Z",
    "langs": ["en"]
  }
}
```

Follow-up payload:

```json
{
  "repo": "did:plc:example",
  "collection": "app.bsky.feed.post",
  "record": {
    "$type": "app.bsky.feed.post",
    "text": "Follow-up message",
    "createdAt": "2026-06-07T12:01:00.000Z",
    "langs": ["en"],
    "reply": {
      "root": {
        "uri": "at://did:plc:example/app.bsky.feed.post/rootRkey",
        "cid": "bafyRootCid"
      },
      "parent": {
        "uri": "at://did:plc:example/app.bsky.feed.post/previousRkey",
        "cid": "bafyPreviousCid"
      }
    }
  }
}
```

Response fields needed:

```json
{
  "uri": "at://did:plc:example/app.bsky.feed.post/newRkey",
  "cid": "bafyNewCid"
}
```

Thread algorithm:

1. Create root record and store its `{ uri, cid }` as both `rootRef` and `previousRef`.
2. For each follow-up, create record with `reply.root = rootRef`, `reply.parent = previousRef`.
3. Save returned `{ uri, cid }` as the new `previousRef`.
4. In `PublishedMessageResponse`, use the returned AT URI as `id` and `uri`; expose the parent AT URI as `replyToId`.

### 6.5 LinkedIn

Official documentation:

- Share on LinkedIn / UGC Posts (`POST https://api.linkedin.com/v2/ugcPosts`, `X-Restli-Protocol-Version`, `X-RestLi-Id`): https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/share-on-linkedin
- Network Update Social Actions / comments (`POST /rest/socialActions/{shareUrn|ugcPostUrn|commentUrn}/comments`, request body fields, comment media errors): https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/network-update-social-actions
- LinkedIn OAuth 2.0 overview: https://learn.microsoft.com/en-us/linkedin/shared/authentication/authentication
- LinkedIn Authorization Code Flow: https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow
- LinkedIn app portal: https://www.linkedin.com/developers/apps

Root post endpoint:

```http
POST https://api.linkedin.com/v2/ugcPosts
Authorization: Bearer {accessToken}
X-Restli-Protocol-Version: 2.0.0
Content-Type: application/json
```

Root text post payload:

```json
{
  "author": "urn:li:person:8675309",
  "lifecycleState": "PUBLISHED",
  "specificContent": {
    "com.linkedin.ugc.ShareContent": {
      "shareCommentary": {
        "text": "Root LinkedIn post"
      },
      "shareMediaCategory": "NONE"
    }
  },
  "visibility": {
    "com.linkedin.ugc.MemberNetworkVisibility": "PUBLIC"
  }
}
```

Root article/link payload uses `shareMediaCategory: "ARTICLE"` and `media[].originalUrl` as documented by Share on LinkedIn.

Root image payload uses `shareMediaCategory: "IMAGE"` and requires prior image upload/registration as documented by Share on LinkedIn.

Root post response requirement:

- Capture `X-RestLi-Id` response header as the root `postId`.
- Preserve the LinkedIn URN/post ID needed to target comments, typically `urn:li:ugcPost:{id}` or the exact returned ID format used by the root post flow.

Normalize the root post target for comments as follows:

```kotlin
fun linkedInPostUrn(postId: String): String =
    if (postId.startsWith("urn:li:")) postId else "urn:li:ugcPost:$postId"
```

When building the Social Actions URL, percent-encode the full URN as one URL path segment:

```text
urn:li:ugcPost:7096760097833439232
-> urn%3Ali%3AugcPost%3A7096760097833439232
```

Comment endpoint:

```http
POST https://api.linkedin.com/rest/socialActions/{urlEncodedRootPostUrn}/comments
Authorization: Bearer {accessToken}
Linkedin-Version: {YYYYMM}
X-Restli-Protocol-Version: 2.0.0
Content-Type: application/json
```

Comment payload:

```json
{
  "actor": "urn:li:person:8675309",
  "object": "urn:li:ugcPost:7096760097833439232",
  "message": {
    "text": "Follow-up LinkedIn comment"
  }
}
```

Comment payload with one image, when the app enables LinkedIn comment image publishing:

```json
{
  "actor": "urn:li:person:8675309",
  "object": "urn:li:ugcPost:7096760097833439232",
  "message": {
    "text": "Follow-up LinkedIn comment"
  },
  "content": [
    {
      "entity": {
        "image": "urn:li:image:C552CAQGu16obsGZENQ"
      }
    }
  ]
}
```

Comment response fields needed:

```json
{
  "commentUrn": "urn:li:comment:(urn:li:ugcPost:7096760097833439232,7102986562019213313)",
  "id": "7102986562019213313",
  "object": "urn:li:ugcPost:7096760097833439232",
  "message": {
    "text": "Follow-up LinkedIn comment"
  }
}
```

Thread algorithm:

1. Reject request if `messages.size > 2`.
2. Publish `messages[0]` as a normal UGC post.
3. If `messages[1]` exists, publish it as a first-level comment on the root post.
4. Return root as `messages[0]` and comment as `messages[1]` with `commentOnId = rootPostId`.

LinkedIn comment constraints:

- Follow-up is a comment, not a second post.
- Follow-up comments use the comment text length limit.
- Follow-up comments support at most one image in this feature.
- If a comment image is sent, represent it with `content[].entity.image` as shown above. Do not include image `description`, `title`, or alt-text fields in comment payloads.
- Reject comment image alt-text behavior rather than dropping alt text silently.
- LinkedIn docs list a common comment creation error for unsupported inline comment images: `403 Unpermitted fields present in REQUEST_BODY ... [/content]`.

## 7. Validation

### 7.1 Effective character counting

Use target-specific validation over every message.

Effective text length:

```kotlin
fun effectiveLength(
    content: String,
    link: String?,
    linkWeight: Int = 25,
    countText: (String) -> Int,
): Int = countText(content) + if (link.isNullOrBlank()) 0 else linkWeight
```

Required counting rules:

- Link weight is `25` for Bluesky, Mastodon, Twitter/X, and LinkedIn validation.
- Bluesky backend validation should count graphemes because Bluesky's post lexicon has `maxGraphemes: 300`.
- Frontend character counting uses Unicode code points and applies the same 25-character link weight for social targets. Backend validation is authoritative where a service requires grapheme counting, especially Bluesky.

### 7.2 Limits

| Target | Root limit | Follow-up limit | Link weight | Additional rules |
| --- | ---: | ---: | ---: | --- |
| `feed` | 1000 | 1000 | 0 for validation; link is rendered separately or appended by presentation | Feed supports multiple messages. |
| `bluesky` | 300 graphemes | 300 graphemes | 25 | Validate every message. |
| `mastodon` | 500 | 500 | 25 | Validate every message. |
| `twitter` | 280 | 280 | 25 | Validate every message. |
| `linkedin` | 2000 | 1250 | 25 | Maximum two messages; follow-up is a comment. |

### 7.3 Preflight validation requirements

For `POST /api/multiple/post`:

1. Parse and normalize request.
2. Ensure `messages` is non-empty.
3. For every selected target, run target validation.
4. If any target fails validation, return a validation error immediately.
5. Do not persist feed posts before validating non-feed targets.
6. Do not call any external social API before all selected targets pass validation.

### 7.4 LinkedIn-specific validation

LinkedIn validation must distinguish root posts from comments:

```text
if target linkedin and messages.size > 2:
  reject: LinkedIn supports at most one follow-up message

validate messages[0] as LinkedIn root post:
  effectiveLength <= 2000
  root post media/link rules apply

if messages[1] exists:
  validate as LinkedIn comment:
    effectiveLength <= 1250
    images.size <= 1
    no unsupported image alt-text flow
```

## 8. Frontend Compatibility

The frontend remains a single-message composer but must use the thread-capable API contract.

### 8.1 Frontend request/response DTOs

```kotlin
@Serializable
internal data class PublishRequestMessage(
    val content: String,
    val link: String? = null,
    val images: List<String>? = null,
)

@Serializable
internal data class PublishRequest(
    val targets: List<String>,
    val language: String? = null,
    val messages: List<PublishRequestMessage>,
)

@Serializable
internal data class ModulePostMessage(
    val id: String,
    val uri: String? = null,
    val replyToId: String? = null,
    val commentOnId: String? = null,
)

@Serializable
internal data class ModulePostResponse(
    val module: String? = null,
    val uri: String? = null,
    val id: String? = null,
    val cid: String? = null,
    val postId: String? = null,
    val messages: List<ModulePostMessage>? = null,
)

@Serializable
internal data class PublishErrorResponse(
    val error: String,
)

@Serializable
internal data class CompositePublishResponse(
    val error: String,
    val responses: List<CompositePublishItem>,
)

@Serializable
internal data class CompositePublishItem(
    val type: String,
    val module: String? = null,
    val status: Int? = null,
    val error: String? = null,
    val result: ModulePostResponse? = null,
)
```

`ModulePostResponse` is intentionally permissive because different target responses expose different root fields.

### 8.2 Frontend submit behavior

Existing single-message UI submits:

```kotlin
PublishRequest(
    targets = formState.targets.toList(),
    language = formState.language,
    messages = listOf(
        PublishRequestMessage(
            content = formState.content,
            link = formState.link.takeIf { it.isNotBlank() },
            images = uploadedImageIds.takeIf { it.isNotEmpty() },
        )
    ),
)
```

Endpoint remains:

```http
POST /api/multiple/post
```

### 8.3 Frontend state/validation compatibility

- Default selected target is `feed`.
- Character counter must use the same platform limits listed in section 7.
- If multiple targets are selected, the UI should show/enforce the strictest relevant limit.
- Frontend serialization tests must prove `messages` is emitted and per-target `messages` response arrays parse.
- For HTTP 400/non-composite errors, parse `PublishErrorResponse` and show `error` to the user.
- For composite partial failures, parse `CompositePublishResponse`; show failed `responses[].module`/`responses[].error` and preserve any `responses[].result` successes in the UI feedback if success reporting is available.

## 9. Implementation Landmarks

Use these as implementation landmarks for locating equivalent concepts in this project:

- `backend/src/main/kotlin/socialpublish/backend/common/Posts.kt`
- `backend/src/main/kotlin/socialpublish/backend/common/ApiError.kt`
- `backend/src/main/kotlin/socialpublish/backend/server/routes/RouteHelpers.kt`
- `backend/src/main/kotlin/socialpublish/backend/server/routes/PublishRoutes.kt`
- `backend/src/main/kotlin/socialpublish/backend/clients/common/SocialMediaApi.kt`
- `backend/src/main/kotlin/socialpublish/backend/modules/PublishModule.kt`
- `backend/src/main/kotlin/socialpublish/backend/modules/FeedModule.kt`
- `backend/src/main/kotlin/socialpublish/backend/db/models.kt`
- `backend/src/main/kotlin/socialpublish/backend/clients/twitter/TwitterApiModule.kt`
- `backend/src/main/kotlin/socialpublish/backend/clients/mastodon/MastodonApiModule.kt`
- `backend/src/main/kotlin/socialpublish/backend/clients/bluesky/BlueskyApiModule.kt`
- `backend/src/main/kotlin/socialpublish/backend/clients/linkedin/LinkedInApiModule.kt`
- `frontend/src/jsMain/kotlin/socialpublish/frontend/pages/PublishFormPage.kt`
- `frontend/src/jsMain/kotlin/socialpublish/frontend/pages/PublishFormState.kt`
- `frontend/src/jsMain/kotlin/socialpublish/frontend/components/CharacterCounter.kt`

## 10. Acceptance Tests

Implement tests before or alongside the rebuild.

### 10.1 Serialization tests

- `NewPostRequest` serializes/deserializes with `messages`.
- Each `NewPostResponse` subtype serializes with `module` and `messages`.
- Frontend `PublishRequest` serializes as `messages: [...]`.
- Frontend `ModulePostResponse` parses every target's response shape.

### 10.2 Validation tests

- Bluesky accepts 300 effective graphemes and rejects 301.
- Mastodon accepts 500 effective chars and rejects 501.
- Twitter/X accepts 280 effective chars and rejects 281.
- LinkedIn root accepts 2000 effective chars and rejects 2001.
- LinkedIn comment accepts 1250 effective chars and rejects 1251.
- LinkedIn with three messages is rejected before any publishing.
- Mixed targets including invalid LinkedIn request produce no feed persistence.

### 10.3 Platform threading tests

- Twitter/X follow-up sends `reply.in_reply_to_tweet_id` equal to previous returned ID.
- Mastodon follow-up sends `in_reply_to_id` equal to previous returned ID.
- Bluesky follow-up sends `reply.root` equal to root strong ref and `reply.parent` equal to previous strong ref.
- LinkedIn follow-up sends a Social Actions comment request to the encoded root post URN and returns `commentOnId`.

### 10.4 Feed tests

- Multi-message feed publish stores one post per message.
- Follow-up feed posts persist `replyToPostUuid` pointing to the previous post.
- Atom output includes `thr:in-reply-to` for non-root entries.

### 10.5 Orchestration tests

- `PublishModule` validates all selected targets before dispatch.
- Publishing runs sequentially within one target.
- `/api/multiple/post` success is keyed by selected target.
- Mixed post-dispatch failures return `CompositeErrorWithDetails` with success and error entries.

## 11. Verified Online Documentation Index

Twitter/X:

- Create post / `POST /2/tweets` / `reply.in_reply_to_tweet_id`: https://docs.x.com/x-api/posts/creation-of-a-post
- X API documentation index: https://docs.x.com/llms.txt

Mastodon:

- Create status / `POST /api/v1/statuses` / `in_reply_to_id`: https://docs.joinmastodon.org/methods/statuses/#create
- Status entity: https://docs.joinmastodon.org/entities/Status/

Bluesky / AT Protocol:

- `com.atproto.repo.createRecord`: https://docs.bsky.app/docs/api/com-atproto-repo-create-record
- `com.atproto.server.createSession`: https://docs.bsky.app/docs/api/com-atproto-server-create-session
- `app.bsky.feed.post` lexicon: https://raw.githubusercontent.com/bluesky-social/atproto/main/lexicons/app/bsky/feed/post.json
- `com.atproto.repo.strongRef` lexicon: https://raw.githubusercontent.com/bluesky-social/atproto/main/lexicons/com/atproto/repo/strongRef.json

LinkedIn:

- Share on LinkedIn / UGC Posts: https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/share-on-linkedin
- Network Update Social Actions / comments: https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/network-update-social-actions
- LinkedIn OAuth overview: https://learn.microsoft.com/en-us/linkedin/shared/authentication/authentication
- LinkedIn Authorization Code Flow: https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow
- LinkedIn app portal: https://www.linkedin.com/developers/apps

Feed/Atom:

- RFC 4685 Atom Threading Extensions: https://www.rfc-editor.org/rfc/rfc4685

## 12. Success Criteria

The reimplementation is complete when:

- Backend accepts `messages` requests on all publish endpoints.
- Legacy single-message forms are normalized into one-message thread requests if compatibility is retained.
- All selected targets are preflight-validated before side effects.
- Mastodon, Twitter/X, and Bluesky produce chained replies.
- LinkedIn produces one root post plus at most one first-level comment.
- Feed persistence stores parent links and Atom output emits `thr:in-reply-to`.
- Backend responses expose ordered per-message IDs plus `replyToId` or `commentOnId` linkage.
- Frontend sends one-message `messages` arrays and parses per-target `messages` response arrays.
- Tests cover serialization, validation, platform request payloads, feed threading, and orchestration failure behavior.
