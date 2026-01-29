# Backend Port Plan (main Kotlin -> Scala 3)

Goal: Bring the Scala 3 backend to feature parity with `main` (Kotlin) by porting LinkedIn, ImageMagick, YouTube OEmbed link previews, and LLM alt-text generation while keeping existing Scala architecture (Tapir + sttp client + Cats Effect).

## Gap inventory (main vs scala3)

- LinkedIn integration is fully implemented in Kotlin (OAuth2 + UGC posts + media upload + link previews) but is missing in Scala (Target.LinkedIn is present but unused).
- Link preview parser (HTML + OpenGraph/Twitter tags) + YouTube OEmbed thumbnail fix exists in Kotlin; Scala has no link preview module.
- Image optimization uses ImageMagick + Tika in Kotlin; Scala uses ImageIO resize only and no external optimizer.
- LLM alt-text generation endpoint `/api/llm/generate-alt-text` exists in Kotlin; Scala has no LLM integration or models.
- AuthStatus/LoginResponse in Scala only includes Twitter; Kotlin includes LinkedIn and updates auth status.
- Server routes: LinkedIn authorize/callback/status + LinkedIn post route, plus LLM endpoint, are missing in Scala.
- Config: Kotlin supports optional LinkedIn/LLM with env/CLI flags; Scala config only has Bluesky/Mastodon/Twitter/Files.

## Port steps (backend)

1. Add LinkedIn module (integration + config + models)
   - New package: `backend/src/main/scala/socialpublish/integrations/linkedin/`
   - Add `LinkedInConfig` with enable flag, client ID/secret, and endpoint defaults (auth/token/api base URLs).
   - Add models mirroring Kotlin: OAuth token/refresh types, UGC post request/response, media upload request/response, status response, enums/constants.
   - Implement `LinkedInApi` interface similar to `TwitterApi`:
     - `buildAuthorizeUrl(jwtToken)`, `handleCallback(code/state/accessToken)`, `hasLinkedInAuth`, `getAuthStatus`, `createPost`.
     - Persist OAuth token and OAuth state in `DocumentsDatabase` via `DocumentTag(name = <key>, kind = "key")` so `searchByKey` works.
     - Token expiry/refresh logic: refresh 5 minutes early; re-save token on refresh.
     - Use `/userinfo` to get person URN; normalize to `urn:li:person:<id>`.
     - Media upload: register upload (`/assets?action=registerUpload`), then upload binary to returned URL.
     - Post creation: build UGC payload for text-only, link previews (ARTICLE), or images (IMAGE), with `explicitNulls = false` in JSON to avoid nulls.
     - Return `NewPostResponse.LinkedIn(postId)` and include in `NewPostResponse` encoder/decoder and Tapir schema.

2. Add Link Preview + YouTube OEmbed utilities
   - New package: `backend/src/main/scala/socialpublish/integrations/linkpreview/`
   - Implement `LinkPreviewParser` with:
     - HTTP fetch (sttp backend) with redirects disabled to avoid bot detection.
     - HTML parsing using Jsoup; extract `og:*`, `twitter:*`, and `<title>/<meta name=description>`.
     - `resolveImageUrl` for relative URLs (port `UrlUtils.kt`).
     - YouTube handling: detect youtube.com / youtu.be URLs; call OEmbed `https://www.youtube.com/oembed` with `maxwidth=1280&maxheight=720`; parse JSON response; do not fallback to HTML fetch on failure.
   - Add models: `LinkPreview`, `YouTubeOEmbedResponse` with lenient JSON decoder.
   - Wire `LinkPreviewParser` into LinkedIn posting for ARTICLE shares (title/thumbnail).

3. Add LLM alt-text generation
   - New package: `backend/src/main/scala/socialpublish/integrations/llm/`
   - Models: `GenerateAltTextRequest`, `GenerateAltTextResponse`, OpenAI-compatible chat request/response models.
   - Add `LlmConfig` with enable flag (apiUrl/apiKey/model).
   - Implement `LlmApi` using sttp client:
     - Read optimized image bytes from `FilesService`.
     - Encode as base64 data URL and call LLM endpoint.
     - Build prompt and include optional user context.
     - Handle timeouts/HTTP errors with `ApiError`.
   - Expose route: `POST /api/llm/generate-alt-text` returning `{altText}`.

4. ImageMagick integration + file pipeline alignment
   - New package: `backend/src/main/scala/socialpublish/integrations/imagemagick/` with:
     - CLI detection for ImageMagick v7 (`magick`) and v6 (`convert`/`identify`).
     - `identifyImageSize(file)` and `optimizeImage(source, dest)` to resize (max 1600x1600) and compress PNG/JPEG.
   - Update `FilesService`:
     - Use Apache Tika (or equivalent) to detect mime types reliably, or keep magic-bytes detection but align with Kotlin behavior.
     - Save original and processed images on disk, similar to Kotlin: `original/` and `processed/` subdirs.
     - Use ImageMagick for optimization; fallback to original on failure.
     - Maintain hash-based storage for dedupe; keep deterministic UUID behavior unless migration is desired.

5. API and auth wiring
   - Update `AuthStatus` to include `linkedin: Boolean` and update login response.
   - Add `LinkedInStatusResponse` model (mirrors Twitter status).
   - Extend Tapir endpoints in `backend/src/main/scala/socialpublish/http/Routes.scala`:
     - `/api/linkedin/authorize`, `/api/linkedin/callback`, `/api/linkedin/status`, `/api/linkedin/post`.
     - `/api/llm/generate-alt-text` (secure endpoint).
     - Add LinkedIn to `/api/multiple/post` with real handler.
   - Update `NewPostResponse` schema to include LinkedIn variant.

6. Config + bootstrapping
   - Extend `AppConfig` to include optional `LinkedInConfig` and `LlmConfig` (enable flag + env/CLI).
   - Wire LinkedIn and LLM modules in `Main.scala` and `HttpServer.scala`.
   - Update `README.md` and `.envrc.sample` with LINKEDIN/LLM env vars.

## Tests (TDD-first)

- LinkedIn:
  - `LinkedInApiSpec` for OAuth token exchange, token refresh, UGC post payload shape, and error handling.
  - Use `SttpBackendStub` and fixed clocks (no sleep).
- Link preview:
  - `LinkPreviewParserSpec` for OpenGraph/Twitter extraction and URL resolution.
  - `YouTubeOEmbedSpec` for OEmbed parsing + YouTube URL detection.
- LLM:
  - `LlmApiSpec` to validate request payload and error mapping.
- Routes:
  - Extend `ServerIntegrationSpec` for LinkedIn status/authorize flow and LLM endpoint.

## Risks / decisions to confirm

- File storage semantics: Scala uses deterministic UUID; Kotlin uses random UUID. If we align with Kotlin, we need a migration plan; otherwise document the difference.
- Link preview HTML fetch policy: keep redirects disabled to match Kotlin bot-avoidance.
- LinkedIn OAuth state storage: use document tags with `key` kind, prefix keys to avoid collisions.
