# Browser Verification Plan

Goal: validate the Compose for Web frontend end-to-end in the browser and surface any runtime issues.

## Prerequisites

- Backend running on `http://localhost:3000` with valid `.envrc`/env vars
- Frontend dev server via `./gradlew :frontend:jsBrowserDevelopmentRun`
- Clean browser session (incognito) to avoid stale cookies/localStorage

## Test Matrix

- **Routing & SPA fallback**: direct-load and reload `/`, `/form`, `/login`, `/account`, unknown path → 404
- **Navigation**: navbar links, browser back/forward, active link highlighting
- **Auth redirects**: protected pages without token redirect to `/login?redirect=...`; bad token clears and redirects
- **Login flow**: submit valid/invalid creds, query param error display, redirect handling (including `redirect` params)
- **Account (Twitter)**: status fetch, connect button redirect, unauthorized handling
- **Publish form**: character counter, target toggles, image upload (<=4), submit success/error modals, disabled states
- **Storage**: cookie `access_token`, localStorage `hasAuth` mutations on login/logout
- **Assets/manifest**: icons, manifest, CSS load; favicon present
- **Proxies**: `/api`, `/rss`, `/files` requests go to backend (check Network tab origin/response)

## Execution Steps

1. Start backend + frontend: `./gradlew :backend:run` (or existing backend) and `./gradlew :frontend:jsBrowserDevelopmentRun`
2. Open `http://localhost:3001`
3. Run through Test Matrix; note console errors/network failures
4. Verify production bundle: `./gradlew :frontend:jsBrowserProductionWebpack` then open `frontend/build/dist/index.html` with simple static server for spot checks

## Reporting

- Record each failure with steps, console error, expected vs actual
- Capture screenshots for visual/layout regressions
- File issues or patch immediately if small (routing/config/runtime)
