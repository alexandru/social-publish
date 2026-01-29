# Frontend Port Plan (main Kotlin Compose -> React TS)

Goal: Bring the React frontend to feature parity with Kotlin Compose UI from `main`, including LinkedIn auth UX, LLM alt-text generation, improved image upload UX, and updated layout/navigation.

## Gap inventory (main vs scala3)

- LinkedIn status + authorize flow in Account page missing in React.
- LLM alt-text generation UX (button on each image card) missing.
- Link-aware character counter (URL counting, per-target limits) missing; current counter is simple string length.
- Publish form state/logic (processing/submitting, per-target max, image upload card UI) missing.
- NavBar styling/icons/links differ (API link, logo, consistent Bulma layout).
- Centralized ApiClient (JSON + error parsing + auth header) missing; code uses raw fetch.
- AuthStatus only stores Twitter; needs LinkedIn too.

## Port steps (frontend)

1. API client and models
   - Add `frontend/src/utils/apiClient.ts` mirroring Kotlin ApiClient:
     - `get`, `post`, `uploadFile` with auth header and JSON error parsing.
   - Add model types in `frontend/src/models/`:
     - `AuthStatus`, `LoginRequest/Response`.
     - `FileUploadResponse`, `PublishRequest`, `ModulePostResponse`.
     - `GenerateAltTextRequest/Response`.

2. Storage updates
   - Extend `frontend/src/utils/storage.ts` to include `linkedin` in `HasAuth`.
   - Update `setAuthStatus/getAuthStatus/updateAuthStatus` to handle LinkedIn.

3. Publish form state and character counting
   - Introduce a `usePublishFormState` or `PublishFormState` module (ports Kotlin logic):
     - `buildPostText(content, link)` and `countCharactersWithLinks` (URL length = 25).
     - Per-target limits (Bluesky 300, Mastodon 500, Twitter 280, LinkedIn 2000).
     - `maxCharacters` based on selected targets; `charactersRemaining`.
     - `isSubmitting`, `isProcessing`, and `isFormDisabled` (including alt-text generation).

4. Image upload UX and alt-text generation
   - Replace `ImageUpload.tsx` with card-based UI:
     - Local preview via FileReader.
     - Alt-text textarea + "Generate Alt-Text" button (calls `/api/llm/generate-alt-text`).
     - Remove button disabled while alt-text generation in progress.
   - Add `AddImageButton` component (hidden file input + button) with max 4 images.
   - Store `uploadedUuid` and `imagePreviewUrl` in image state.
   - Disable form while processing uploads or alt-text generation.

5. Publish flow behavior
   - Use ApiClient for uploads and post submission.
   - Ensure 401 redirects to `/login?error=...&redirect=/form`.
   - Display success info modal (link to `/rss`).
   - Keep form reset logic consistent with Kotlin (reset state, clear images).

6. Account page (Twitter + LinkedIn)
   - Add LinkedIn status fetch (`/api/linkedin/status`) and storage update.
   - Add LinkedIn authorize button with preflight check:
     - If status returns 503/500, show alert for missing config.
   - Maintain Twitter status behavior as-is.

7. NavBar + layout improvements
   - Update NavBar to match Kotlin layout:
     - Brand logo, primary buttons with FontAwesome icons.
     - Add API link to `/docs` (opens new tab).
   - Add `PageContainer` component (`section` + `container is-max-desktop`) and wrap pages.
   - Ensure Bulma + FontAwesome CSS is loaded (add to `frontend/src/index.tsx` or `index.html`).

8. ModalMessage and Authorize behavior
   - Update `ModalMessage` to support ESC key + overlay click to close (as in Kotlin).
   - Simplify `Authorize` to redirect immediately when no token (match Kotlin), or keep modal but ensure UX is consistent with current flows.

## Tests / checks

- UI test coverage is minimal today; add lightweight component tests only if test harness exists.
- Manual QA checklist:
  - Login + redirect flow
  - Twitter + LinkedIn connect flow
  - Image upload preview, alt-text generation, and submission
  - Character count behavior for URL + multiple targets

## Assets / dependencies

- Add FontAwesome CSS package or use existing icon system consistently.
- Ensure `public/` assets include app icons if using the Kotlin branding.
