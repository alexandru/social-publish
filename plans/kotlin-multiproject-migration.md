# Plan: Kotlin Multi-Project + KotlinJS Frontend

## Goals

- Introduce a root Gradle multi-project build that includes `backend-kotlin` and new `frontend-kotlin`.
- Add a Kotlin/JS + Kotlin React frontend that ports `frontend/` without breaking existing behavior.
- Update dev tooling (Makefile/scripts) to run both Kotlin backend and frontend.
- Update Dockerfile to build and run Kotlin backend + frontend.
- Keep `frontend/` and `backend/` directories, remove other root leftovers once replaced.

## Progress Checklist

- [x] Root Gradle multi-project setup completed
- [x] `frontend-kotlin` module structure and assets created
- [x] Kotlin/JS frontend ported to Kotlin sources
- [x] Makefile + Dockerfile.kotlin updated for Kotlin workflow
- [x] `.gitignore` and README updated for Kotlin build
- [x] Replace react-router with lightweight internal router
- [x] Build with latest kotlin-wrappers only (no legacy)
- [x] Re-run `./gradlew build` after routing changes
- [ ] Verify webpack dev server proxy + production bundle
- [x] Add `.envrc` defaults for backend config

## Frontend Analysis (Current `frontend/`)

### Build System & Dev Environment

- **Current**: Vite with `@vitejs/plugin-react`, port 3001
  - `vite.config.ts` config: proxy `/rss`, `/api`, `/files`, sourcemaps enabled
  - `index.html` mounts into `<div id="app">` with `src="/src/index.tsx"`
- **KotlinJS**: **Webpack 5** (bundled with Kotlin Multiplatform plugin)
  - Uses `webpack-dev-server` for dev mode
  - Uses **Yarn** as package manager (NOT npm!)
  - Gradle plugin auto-generates `webpack.config.js` at build time
- **Dev server**: Port 3001 with proxy to backend (port 3000) for `/rss`, `/api`, `/files`
- **TypeScript**: Target ES2020, module ESNext, React JSX transform
- **Total code**: ~892 lines across 10 TypeScript files

### Routes & Pages

- `/` → Home (simple landing page with links to RSS and GitHub)
- `/form` → PublishFormPage (main post creation form, requires auth)
- `/login` → Login (username/password form)
- `/account` → Account (Twitter OAuth connection status/button)
- `*` → NotFound (404 page)

### Styling & UI

- **CSS Framework**: Bulma 1.0.4 (imported via `@import 'bulma/css/bulma.css'` in `src/style.css`)
- **Icons**: ionicons 8.0.13 (imported as ESM: `import { logoTwitter, logOut, ... } from 'ionicons/icons'`)
- **Component styles**: Minimal component-specific CSS in `Login/style.css` and `PublishFormPage/style.css`
- **Layout**: `NavBar` component always rendered, then `<main>` with routed content

### Static Assets

- `public/manifest.json` - PWA manifest (name, icons, theme color #04d1b1, start_url: /form)
- `public/assets/logos/cloud.svg` - main icon
- `public/assets/logos/cloud-256x256.png` - PNG icon
- `public/assets/logos/cloud-48x48.webp` - WebP icon
- `public/assets/logos/cloud-256x256.ico` - favicon
- All referenced in `index.html` via `<link>` tags

### Authentication & Storage

- **JWT Token**: Stored in cookie `access_token` with 2-day expiry
- **Auth Status**: localStorage key `hasAuth` with shape `{ twitter: boolean }`
- **Cookie helpers**: `setCookie`, `clearCookie`, `cookies()` parse `document.cookie`
- **LocalStorage helpers**: `storeObjectInLocalStorage`, `getObjectFromLocalStorage`

### API Interactions

1. **POST `/api/login`**: `{ username, password }` → `{ token, hasAuth }`
2. **GET `/api/twitter/status`**: Returns `{ hasAuthorization, createdAt? }`
3. **Redirect `/api/twitter/authorize`**: OAuth flow via `window.location.href`
4. **POST `/api/files/upload`**: FormData with `file` + optional `altText` → `{ uuid }`
5. **POST `/api/multiple/post`**: `{ content, link?, targets[], images[], rss?, cleanupHtml? }`
6. **External links**: `/rss`, `/rss/target/linkedin`

### Component Breakdown

- **App** (index.tsx): BrowserRouter + NavBar + Routes
- **NavBar**: Responsive navbar with burger menu, conditional login/logout/account buttons
- **Authorize**: HOC that checks JWT token, shows error modal and redirects to `/login?redirect=...` if missing
- **ModalMessage**: Bulma modal with message types (info, warning, error), dismiss on click/ESC
- **ImageUpload**: File input + alt text textarea, emits `SelectedImage { id, file?, altText? }`
- **Home**: Static landing page
- **Login**: Form with username/password, handles `/login?error=...&redirect=...` query params
- **Account**: Twitter OAuth status display + "Connect X (Twitter)" button
- **PublishFormPage**: Complex form with:
  - Content textarea
  - Optional link input
  - Up to 4 image uploads
  - Target checkboxes (Mastodon, Bluesky, Twitter, LinkedIn)
  - Cleanup HTML checkbox
  - Character counter (280 limit for content + link)
  - Form disable during submit
  - Reset button clears state
- **NotFound**: 404 page

### Behavioral Details

- **Character counting**: Concatenates content + link with `\n\n`, shows `280 - text.length`
- **Twitter checkbox**: Disabled if `!hasAuth.twitter`
- **Image uploads**: Sequential uploads before post creation, each returns UUID
- **Form validation**: Content required, at least one target required
- **Error handling**: 401/403 redirects to `/login?error=...&redirect=/form`
- **Success flow**: Form reset + info modal with link to RSS feed
- **Navbar state**: Active link highlighting based on `pathname`, burger menu toggle

## Kotlin/JS + React Dependencies (from docs + analysis)

### Gradle Plugin Configuration

- Use `kotlin("multiplatform")` for modern setup
- Configure `js(IR) { browser { binaries.executable() } }` for browser target with IR compiler
- **Webpack is bundled** - Gradle plugin auto-downloads and configures webpack 5
- **Yarn is bundled** - Plugin manages its own Yarn instance for npm deps
- Dev server via `jsBrowserDevelopmentRun` task (uses `webpack-dev-server`)
- Production build via `jsBrowserProductionWebpack` task (minified)
- CSS support: `browser { commonWebpackConfig { cssSupport { enabled.set(true) } } }`

### Kotlin Wrappers (React ecosystem)

- **Versioning**: use explicit wrapper versions (Gradle `platform`/`enforcedPlatform` deprecated in Kotlin 2.3)
  - `implementation("org.jetbrains.kotlin-wrappers:kotlin-react:2026.1.5-19.2.3")`
  - `implementation("org.jetbrains.kotlin-wrappers:kotlin-react-dom:2026.1.5-19.2.3")`
  - `implementation("org.jetbrains.kotlin-wrappers:kotlin-browser:2026.1.5")`
  - `implementation("org.jetbrains.kotlin-wrappers:kotlin-web:2026.1.5")`
- **Routing**: use lightweight internal router (no react-router dependency)
- **CSS-in-Kotlin** (optional): `implementation("org.jetbrains.kotlin-wrappers:kotlin-emotion")`
  - Only if we want type-safe inline styles; NOT required for Bulma classes

### NPM Dependencies (via Gradle)

- `npm("bulma", "1.0.4")` - CSS framework
- `npm("ionicons", "8.0.13")` - icon library
- NO need for `react`, `react-dom`, `react-router-dom` as NPM deps - wrappers bundle them
- Yarn will auto-install these during `kotlinNpmInstall` Gradle task
- `kotlin-js-store/yarn.lock` will be generated for version locking

### Browser APIs & Helpers

- `kotlinx.browser.document` - access DOM
- `kotlinx.browser.window` - access Window, fetch API
- `org.w3c.fetch.RequestInit` - type-safe fetch configuration
- `org.w3c.dom.HTMLInputElement`, `HTMLFormElement` - DOM element types
- `org.w3c.files.File`, `FormData` - file upload support

### Custom Helpers Needed

```kotlin
@JsName("require")
external fun jsRequire(name: String): dynamic

// HTML attribute extensions for data-* attributes
var AnchorHTMLAttributes<*>.dataTarget: String?
    get() = this["data-target"]
    set(value) { this["data-target"] = value }

operator fun HTMLAttributes<*>.get(key: String): String?
operator fun HTMLAttributes<*>.set(key: String, value: String?)

// Lightweight router helpers
fun navigateTo(path: String)
fun useCurrentPath(): String
```

### Entry Point Pattern

```kotlin
fun main() {
    jsRequire("./style.css") // Load Bulma
    val container = document.getElementById("app") ?: error("...")
    createRoot(container).render(App.create())
}
```

### Webpack Configuration

- Gradle plugin auto-generates `build/js/packages/projectName/webpack.config.js`
- Custom config via `webpack.config.d/*.js` files in project root
- CSS loader enabled via `cssSupport { enabled.set(true) }`
- Dev server proxy configured in `browser { runTask { devServer { proxy = ... } } }`
- Example custom config for CSS (if needed beyond default):
  ```js
  // webpack.config.d/custom.js
  config.module.rules.push({
    test: /\.css$/,
    use: ['style-loader', 'css-loader']
  })
  ```

## Phase 0: Discovery + Constraints

**COMPLETED ANALYSIS:**

### Current Root Build System

- Root `package.json` with npm scripts: `init`, `dev`, `build`, `clean`
- `dev` script: `concurrently` runs both backend and frontend dev servers
- `build` script: builds both backend and frontend, then runs `./scripts/package.sh`
- Uses npm workspaces pattern (--prefix for backend/frontend)

### Backend-Kotlin Gradle Setup

- **Kotlin version**: 2.3.0
- **JVM toolchain**: Java 17
- **Build system**: Gradle 8.11+ with Kotlin DSL
- **Plugins**: `kotlin("jvm")`, `kotlin("plugin.serialization")`, `application`, `ktlint`, `com.github.ben-manes.versions`
- **Version catalog**: Uses `settings.gradle.kts` with custom `libs` catalog
- **Output**: Fat JAR at `build/libs/social-publish-1.0.0.jar`
- **Main class**: `com.alexn.socialpublish.MainKt`
- Currently standalone project with own `settings.gradle.kts`

### Dockerfiles Analysis

1. **Dockerfile** (Node.js-based, legacy):
   - Multi-stage: Alpine + Node.js for build and runtime
   - Builds both `backend/` and `frontend/` (npm-based)
   - Output: `/app/dist` with packaged Node.js backend
2. **Dockerfile.kotlin** (Target for migration):
   - Stage 1: Gradle 8.11-jdk17-alpine builds backend-kotlin
   - Stage 2: Node 20-alpine builds frontend (Vite)
   - Stage 3: eclipse-temurin:17-jre-alpine runtime
   - Serves frontend static files from `/app/public`
   - Backend serves at port 3000, static files likely via Ktor

### Makefile Targets

- `build-production`, `push-production-latest`, `push-production-release`: Docker buildx multi-arch
- `build-local`, `run-local`: Local Docker build/run with env vars
- `update`: npm-check-updates for root + backend + frontend
- **MIGRATION NEEDED**: Add Gradle tasks for frontend-kotlin

### AGENTS.md Rules (backend-kotlin)

- TDD required: write failing tests first
- Idiomatic Kotlin: direct style with `suspend`, avoid monad chaining
- Arrow for `Resource` and `Either`
- Prefer top-level classes, avoid inner classes except sealed hierarchies
- Feature-based packaging, not MVC grouping
- ktlintFormat required before commits

### Assets & Resources to Copy

- `frontend/public/manifest.json` → `frontend-kotlin/src/jsMain/resources/manifest.json`
- `frontend/public/assets/logos/*` → `frontend-kotlin/src/jsMain/resources/assets/logos/*`
- `frontend/index.html` → `frontend-kotlin/src/jsMain/resources/index.html` (modify script src)
- Component-specific CSS files (minimal, mostly empty)

### Critical Compatibility Requirements

1. Dev server must run on port 3001 with same proxy config
2. All API endpoints must work identically
3. Auth flow (cookie + localStorage) must match exactly
4. Form behavior (character count, validation, uploads) must be pixel-perfect
5. Bulma CSS classes must be preserved exactly
6. PWA manifest and icons must be identical

## Phase 1: Root Gradle Multi-Project Setup

### 1.1: Create Root Gradle Configuration

- Create `settings.gradle.kts` in root:

  ```kotlin
  rootProject.name = "social-publish-multiproject"

  include("backend-kotlin")
  include("frontend-kotlin")

  dependencyResolutionManagement {
      versionCatalogs {
          // Move existing libs catalog from backend-kotlin/settings.gradle.kts here
          create("libs") { /* existing versions + libraries */ }
      }
  }
  ```

- Create `build.gradle.kts` in root for shared configuration:

  ```kotlin
  plugins {
      kotlin("multiplatform") version "2.3.0" apply false
      kotlin("jvm") version "2.3.0" apply false
      kotlin("plugin.serialization") version "2.3.0" apply false
  }

  allprojects {
      repositories {
          mavenCentral()
      }
  }
  ```

### 1.2: Adapt backend-kotlin for Multi-Project

- Remove `backend-kotlin/settings.gradle.kts` (root settings will govern)
- Keep version catalog in root `settings.gradle.kts`
- Update `backend-kotlin/build.gradle.kts`:
  - Preserve `group` + `version` (needed for jar naming compatibility)
  - Ensure all plugins are applied, not just declared
  - Keep all existing dependencies and tasks

### 1.3: Copy Gradle Wrapper to Root

- Copy `backend-kotlin/gradle/wrapper/*` to root `gradle/wrapper/`
- Copy `backend-kotlin/gradlew` and `backend-kotlin/gradlew.bat` to root
- Merge `backend-kotlin/gradle.properties` into root `gradle.properties`

### 1.4: Verification Steps

- `./gradlew projects` - should show both modules
- `./gradlew :backend-kotlin:build` - should build successfully
- `./gradlew :backend-kotlin:run` - should run backend
- Ensure fat JAR still builds at `backend-kotlin/build/libs/social-publish-1.0.0.jar`

## Phase 2: KotlinJS + Kotlin React Frontend (Detailed Implementation)

### 2.1: Create frontend-kotlin Module Structure

```
frontend-kotlin/
├── build.gradle.kts
└── src/
    └── jsMain/
        ├── kotlin/
        │   └── com/alexn/socialpublish/frontend/
        │       ├── Main.kt (entry point)
        │       ├── App.kt
        │       ├── utils/
        │       │   └── Storage.kt
        │       ├── components/
        │       │   ├── NavBar.kt
        │       │   ├── Authorize.kt
        │       │   ├── ModalMessage.kt
        │       │   └── ImageUpload.kt
        │       └── pages/
        │           ├── Home.kt
        │           ├── Login.kt
        │           ├── Account.kt
        │           ├── PublishFormPage.kt
        │           └── NotFound.kt
        └── resources/
            ├── index.html
            ├── style.css
            ├── manifest.json
            └── assets/
                └── logos/
                    ├── cloud.svg
                    ├── cloud-256x256.png
                    ├── cloud-48x48.webp
                    └── cloud-256x256.ico
```

### 2.2: build.gradle.kts for frontend-kotlin

```kotlin
plugins {
    kotlin("multiplatform")
}

kotlin {
    js(IR) {
        browser {
            binaries.executable()

            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }

            runTask {
                devServer = devServer?.copy(
                    port = 3001,
                    proxy = mutableMapOf(
                        "/api" to "http://localhost:3000",
                        "/rss" to "http://localhost:3000",
                        "/files" to "http://localhost:3000"
                    )
                )
            }

            webpackTask {
                outputFileName = "app.js"
            }
        }
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                // Kotlin wrappers BOM (verify React version compatibility)
                implementation(enforcedPlatform("org.jetbrains.kotlin-wrappers:kotlin-wrappers-bom:1.0.0-pre.810"))

                // React core
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react-dom")

                // Browser APIs
                implementation("org.jetbrains.kotlin-wrappers:kotlin-browser")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-web")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.2")

                // NPM dependencies (managed by Yarn)
                implementation(npm("bulma", "1.0.4"))
                implementation(npm("ionicons", "8.0.13"))
            }
        }
    }
}
```

**Key differences from original draft:**

- `runTask` configures `devServer` for webpack-dev-server
- `webpackTask` sets output filename
- No Vite-specific config
- Yarn will manage npm deps automatically
- Verify wrappers BOM React version vs current frontend (React 19)

### 2.3: Port Component Structure (File-by-File)

#### Main.kt

```kotlin
import kotlinx.browser.document
import react.create
import react.dom.client.createRoot

@JsName("require")
external fun jsRequire(name: String): dynamic

fun main() {
    jsRequire("./style.css")
    val container = document.getElementById("app") ?: error("Couldn't find app container!")
    createRoot(container).render(App.create())
}
```

#### App.kt

```kotlin
import react.FC
import react.Props
import react.dom.html.ReactHTML.main

val App = FC<Props> {
    val currentPath = useCurrentPath()
    NavBar()
    main {
        when (currentPath) {
            "/" -> Home()
            "/form" -> PublishFormPage()
            "/login" -> Login()
            "/account" -> Account()
            else -> NotFound()
        }
    }
}
```

#### utils/Storage.kt

- Port all cookie and localStorage helpers
- Use `document.cookie` string parsing
- Use `kotlinx.browser.window.localStorage`
- Types: `HasAuth` data class, extension functions for cookies

#### components/NavBar.kt

- Use `var navbarIsActive by useState("")` for burger menu state
- Use `useCurrentPath()` hook for active link detection
- Import icons from ionicons: `external val homeOutline: String` declarations
- Bulma classes: `navbar`, `navbar-brand`, `navbar-burger`, `navbar-menu`, etc.

#### components/Authorize.kt

- Higher-order component pattern using FC
- Check JWT token via `getJwtToken()`
- Use `navigateTo()` helper for redirect
- Show `ModalMessage` if unauthorized

#### components/ModalMessage.kt

- Props: `type: MessageType, children: ReactNode?, isEnabled: Boolean, onDisable: () -> Unit`
- Use `onClick` and `onKeyDown` handlers
- Bulma modal classes with `is-active` state

#### components/ImageUpload.kt

- Props: `id: Int, state: SelectedImage, onSelect: (SelectedImage) -> Unit, onRemove: (Int) -> Unit`
- File input with `accept="image/*"`
- Handle `FileList` from `HTMLInputElement.files`
- Bulma file upload styling

#### pages/Home.kt

- Simple FC with static content
- Bulma classes: `section`, `container`, `title`, `subtitle`, `box`, `content`

#### pages/Login.kt

- Form state: `var username by useState("")`, `var password by useState("")`
- Error state management
- Query param parsing: `window.location.search` + `URLSearchParams`
- Fetch API for `/api/login`
- Cookie and localStorage updates on success

#### pages/Account.kt

- Fetch `/api/twitter/status` on mount (useEffect)
- OAuth redirect via `window.location.href`
- Display connection status
- Ionicons integration for Twitter logo

#### pages/PublishFormPage.kt (Most Complex)

- Multiple state hooks:
  - `var data by useState<FormData>(FormData())`
  - `var images by useState<Map<Int, SelectedImage>>(emptyMap())`
- Character counter logic
- Dynamic image upload components (add/remove)
- Form field disable during submit
- Sequential image uploads then post creation
- Error/success modal management
- Form reset after success

#### pages/NotFound.kt

- Simple 404 page with Bulma styling

### 2.4: External Declarations for NPM Packages

#### Ionicons.kt

```kotlin
@JsModule("ionicons/icons")
@JsNonModule
external val homeOutline: String
external val play: String
external val logoGithub: String
external val logoTwitter: String
external val logIn: String
external val logOut: String
```

### 2.5: HTML & Resources

- Copy `index.html` from `frontend/index.html`
- Change `<div id="app">` from `<div id="root">`
- Update script src to generated JS filename
- Copy all assets from `frontend/public/` to `src/jsMain/resources/`
- Copy `manifest.json` with no changes

### 2.6: CSS Integration

- **KotlinJS uses webpack CSS loaders**, enabled via `cssSupport { enabled.set(true) }`
- Create `src/jsMain/resources/style.css` with `@import 'bulma/css/bulma.css';`
- Load via `jsRequire("./style.css")` in `main()`
- Webpack will process the CSS import and bundle it
- Bulma CSS will be loaded from `node_modules/bulma/css/bulma.css` (installed by Yarn)
- Component-specific styles inline via Bulma classes (no CSS-in-JS needed)
- If additional webpack config needed, create `webpack.config.d/css.js`:
  ```js
  // This is usually not needed as cssSupport handles it
  // But available if custom loaders required
  ```

### 2.7: Type Definitions Needed

```kotlin
// FormData type for post creation
data class FormData(
    val content: String = "",
    val link: String? = null,
    val targets: List<Target> = emptyList(),
    val rss: String? = null,
    val cleanupHtml: Boolean = false
)

enum class Target {
    @JsonName("mastodon") MASTODON,
    @JsonName("twitter") TWITTER,
    @JsonName("bluesky") BLUESKY,
    @JsonName("linkedin") LINKEDIN
}

data class SelectedImage(
    val id: Int,
    val file: File? = null,
    val altText: String? = null
)

data class HasAuth(
    val twitter: Boolean = false
)

enum class MessageType {
    INFO, WARNING, ERROR
}
```

### 2.8: Fetch API Wrappers

- Helper functions for type-safe fetch calls
- JSON serialization/deserialization
- Error handling for 401/403 redirects
- FormData construction for file uploads

### 2.9: Testing & Validation Checklist

- [ ] `./gradlew :frontend-kotlin:jsBrowserDevelopmentRun` starts dev server on port 3001
- [ ] Webpack dev server proxies requests to backend (port 3000)
- [ ] Proxy works for /api, /rss, /files endpoints
- [ ] Login flow works (cookie + localStorage)
- [ ] Form submission works (images + post creation)
- [ ] Twitter OAuth redirect works
- [ ] Character counter accurate (280 - content.length - link.length)
- [ ] Image upload (up to 4 images, sequential upload)
- [ ] All Bulma styles render correctly (CSS loaded via webpack)
- [ ] Burger menu works on mobile (navbar state toggle)
- [ ] 404 page renders for unknown routes
- [ ] PWA manifest loads from resources
- [ ] Favicons display correctly from resources
- [ ] Production build: `./gradlew :frontend-kotlin:jsBrowserProductionWebpack`
- [ ] Production output in `build/dist` directory
- [ ] Webpack bundles CSS with JS correctly
- [ ] `kotlin-js-store/yarn.lock` generated and committed

## Phase 3: Developer Tooling

### 3.1: Update Makefile

Add targets for Kotlin-based development:

```makefile
# Development targets for Kotlin stack
dev-kotlin:
	./gradlew :backend-kotlin:run & ./gradlew :frontend-kotlin:jsBrowserDevelopmentRun --continuous

dev-kotlin-backend:
	./gradlew :backend-kotlin:run

dev-kotlin-frontend:
	./gradlew :frontend-kotlin:jsBrowserDevelopmentRun --continuous

build-kotlin:
	./gradlew build

# Keep existing npm-based targets for backward compatibility
dev:
	npm run dev

build:
	npm run build
```

### 3.2: Update Docker Build

Modify `Dockerfile.kotlin` to build frontend-kotlin:

- Stage 1: Build both backend-kotlin AND frontend-kotlin with Gradle
- Stage 2: Remove Node.js frontend build (replaced by KotlinJS)
- Stage 3: Copy both JARs and static assets from webpack build
- Static files from `frontend-kotlin/build/dist` → `/app/public`

### 3.3: Scripts

- Keep `scripts/package.sh`, `scripts/new-version.sh` (may need updates for multi-project)
- Keep `scripts/docker-entrypoint.sh` (no changes needed)
- Legacy `frontend/` and `backend/` npm scripts remain unchanged

## Phase 4: Docker Build + Run

### 4.1: Update Dockerfile.kotlin Multi-Stage Build

```dockerfile
# Stage 1: Build backend-kotlin + frontend-kotlin with Gradle
FROM gradle:8.11-jdk17-alpine AS build

WORKDIR /app

# Copy Gradle files
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY backend-kotlin/build.gradle.kts ./backend-kotlin/
COPY frontend-kotlin/build.gradle.kts ./frontend-kotlin/

# Copy source code
COPY backend-kotlin/src ./backend-kotlin/src
COPY frontend-kotlin/src ./frontend-kotlin/src

# Build both projects
RUN gradle :backend-kotlin:build :frontend-kotlin:jsBrowserProductionWebpack --no-daemon -x test

# Stage 2: Runtime with JRE
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Create user
RUN adduser -u 1001 -h /app -s /bin/sh -D appuser
RUN chown -R appuser /app && chmod -R "g+rwX" /app
RUN mkdir -p /var/lib/social-publish
RUN chown -R appuser /var/lib/social-publish && chmod -R "g+rwX" /var/lib/social-publish

# Copy backend JAR
COPY --from=build --chown=appuser:root /app/backend-kotlin/build/libs/social-publish-1.0.0.jar /app/app.jar

# Copy frontend webpack bundle
COPY --from=build --chown=appuser:root /app/frontend-kotlin/build/dist /app/public

# Expose port 3000
EXPOSE 3000
USER appuser

# Environment variables
ENV DB_PATH=/var/lib/social-publish/sqlite3.db
ENV UPLOADED_FILES_PATH=/var/lib/social-publish/uploads
ENV HTTP_PORT=3000

RUN mkdir -p "${UPLOADED_FILES_PATH}"

# Run the application
CMD ["java", "-jar", "/app/app.jar"]
```

### 4.2: Verify Backend Serves Static Files

- Ensure backend-kotlin Ktor config serves static files from `/app/public`
- Index.html routing for SPA (all routes → index.html)
- Static assets served from `/assets/*`

### 4.3: Build & Test

```bash
docker build -f Dockerfile.kotlin -t social-publish:kotlin-test .
docker run -p 3000:3000 social-publish:kotlin-test
# Visit http://localhost:3000 and verify app works
```

## Phase 5: Cleanup + Verification

### 5.1: Root Cleanup (Must Keep `frontend/` + `backend/`)

Remove/deprecate (after Kotlin build fully replaces them):

- Root `package.json`, `package-lock.json` (npm-based orchestrator)
- Root `Dockerfile` (legacy Node-based build) if replaced by Kotlin-based Dockerfile
- Root `opencode.json` (if only used for old workflow)
- Root `scripts/package.sh` (if only used by npm build)

Keep:

- `frontend/` and `backend/` (explicit requirement)
- Kotlin build files, Gradle wrapper, `frontend-kotlin/`, `backend-kotlin/`
- `Dockerfile.kotlin` (or rename to `Dockerfile` if replacing legacy)

Update `.gitignore` for Gradle multi-project:

```
# Gradle
.gradle/
build/

# Kotlin/JS
kotlin-js-store/

# Keep existing entries
```

**Note**: Confirm deletions before removing any legacy files to preserve compatibility.

# Gradle

.gradle/
build/
kotlin-js-store/

# Keep existing entries

```

### 5.2: Verification Matrix

| Task                  | Command                                                  | Expected Result                              |
| --------------------- | -------------------------------------------------------- | -------------------------------------------- |
| Backend build         | `./gradlew :backend-kotlin:build`                        | JAR in `backend-kotlin/build/libs/`          |
| Backend run           | `./gradlew :backend-kotlin:run`                          | Server on port 3000                          |
| Frontend build (dev)  | `./gradlew :frontend-kotlin:jsBrowserDevelopmentWebpack` | JS in `frontend-kotlin/build/dist/`          |
| Frontend build (prod) | `./gradlew :frontend-kotlin:jsBrowserProductionWebpack`  | Minified JS in `frontend-kotlin/build/dist/` |
| Frontend dev server   | `./gradlew :frontend-kotlin:jsBrowserDevelopmentRun`     | webpack-dev-server on port 3001              |
| Full build            | `./gradlew build`                                        | Both modules build                           |
| Docker build          | `docker build -f Dockerfile.kotlin .`                    | Multi-stage build succeeds                   |
| Legacy npm build      | `npm run build`                                          | Old `backend/` + `frontend/` still work      |

### 5.3: Commit kotlin-js-store

- Add `kotlin-js-store/yarn.lock` to Git
- This locks dependency versions for reproducible builds
- Update `.gitignore` to NOT ignore `kotlin-js-store/`

### 5.4: Documentation Updates

- Update README.md with new Gradle commands
- Document both npm (legacy) and Gradle (new) workflows
- Add migration guide for developers

## Deliverables

- Root multi-project Gradle setup with `settings.gradle.kts`
- New `frontend-kotlin` module using KotlinJS + React + Webpack
- Updated Makefile/scripts for unified dev workflow
- Updated `Dockerfile.kotlin` for Kotlin backend + frontend build
- Cleaned root with `kotlin-js-store/yarn.lock` committed
- 100% feature parity with existing `frontend/` (all 892 lines ported)

## Critical Technical Notes

### Webpack vs Vite Differences

- **Vite**: ES modules, instant HMR, unbundled dev mode
- **Webpack 5 (KotlinJS)**: Bundled dev mode, traditional HMR
- Both support CSS imports, both proxy backend
- Performance may differ but functionality identical

### Yarn Lock File Management

- `kotlin-js-store/yarn.lock` is auto-generated
- **MUST** commit to version control
- Ensures reproducible builds across machines
- Updated by `kotlinNpmInstall` Gradle task

### Icon Library Compatibility

- Ionicons 8.0.13 exports icon names as strings (e.g., `homeOutline: string`)
- Must create `external` declarations for each icon
- Icons are SVG paths, rendered via `<img src={iconName}>` in Kotlin
- Alternative: Use data URLs or inline SVGs if external declarations problematic

### CSS Import Strategy

- `@import 'bulma/css/bulma.css'` in `style.css` works via webpack CSS loader
- Bulma installed to `node_modules/` by Yarn
- Webpack resolves node_modules imports automatically
- Component CSS files can be imported same way

### FormData and File Uploads

- Use `org.w3c.files.File` from Kotlin stdlib
- `FormData` available via `org.w3c.fetch.FormData`
- File input: `HTMLInputElement.files` returns `FileList?`
- Must convert `FileList` to `File` for upload

### Browser API Typing

- `kotlinx.browser.document` - typed DOM access
- `kotlinx.browser.window` - typed Window API
- `window.fetch()` returns `Promise<Response>`
- Must use `.await()` in suspend functions OR `.then {}` callbacks

### Routing Strategy

- No react-router dependency (keeps wrappers single-version)
- Use lightweight internal router with `window.history.pushState`
- Provide `useCurrentPath()` hook and `navigateTo()` helper
- Render page via `when (currentPath)` in `App`

### State Management Patterns

- `var state by useState(initialValue)` - delegated property syntax
- `val (state, setState) = useState(initialValue)` - destructured syntax
- Both work, delegated is more Kotlin-idiomatic
- State updates trigger re-render automatically

### Known Limitations

1. **No Vite dev features**: No unbundled dev mode, no native ESM
2. **Webpack bundle size**: Larger than Vite (but minified in production)
3. **Build speed**: Webpack slower than Vite (but Gradle caching helps)
4. **HMR**: Works but may be less reliable than Vite's HMR
5. **Kotlin/JS overhead**: Small runtime overhead vs pure JS

### Migration Risks

- **Ionicons compatibility**: May need custom icon loader if `external` declarations fail
- **CSS @import resolution**: Webpack vs Vite may resolve paths differently
- **Proxy config**: webpack-dev-server proxy may behave differently than Vite's
- **File upload**: Browser File API edge cases (check FileList handling)
- **React version mismatch**: Wrappers BOM may include different React version than frontend uses
- **TypeScript types**: No `.d.ts` files in Kotlin, rely on external declarations

### Testing Strategy

1. Port one page at a time (start with Home, simplest)
2. Test each page in isolation before wiring router
3. Verify API calls with real backend running
4. Compare DOM structure between TS and Kotlin versions
5. Visual regression testing (screenshot comparison)
6. Test in multiple browsers (Chrome, Firefox, Safari)
7. Test mobile responsiveness (burger menu critical)
```
