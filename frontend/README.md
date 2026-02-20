# Frontend-Compose

A Compose Multiplatform for Web frontend for the Social Publish application.

## Features

- **Login/Logout** - Full authentication flow with JWT token management
- **Error Handling** - Modal displays for errors during login
- **Responsive Navigation** - Top navigation bar that changes based on authentication state
- **Compose for Web** - Modern declarative UI using Jetpack Compose for Web
- **Bulma CSS** - Clean, responsive styling

## Technology Stack

- **Kotlin/JS** - Kotlin compiled to JavaScript
- **Compose Multiplatform** 1.7.3 - Declarative UI framework
- **kotlinx.serialization** - JSON serialization/deserialization
- **Bulma CSS** 1.0.4 - CSS framework
- **Webpack Dev Server** - Hot reload development server

## Project Structure

```
frontend/
├── build.gradle.kts                    # Build configuration
├── webpack.config.d/
│   └── dev-server.js                   # Webpack configuration
└── src/jsMain/
    ├── kotlin/com/alexn/socialpublish/
    │   ├── Main.kt                     # Application entry point
    │   ├── components/
    │   │   ├── ErrorModal.kt           # Error display modal
    │   │   └── NavBar.kt               # Top navigation bar
    │   ├── models/
    │   │   └── Auth.kt                 # Authentication data models
    │   ├── pages/
    │   │   ├── HomePage.kt             # Home page
    │   │   └── LoginPage.kt            # Login page
    │   └── utils/
    │       ├── ApiClient.kt            # HTTP client for API calls
    │       └── Storage.kt              # LocalStorage wrapper
    └── resources/
        └── index.html                  # HTML template
```

## Development

### Prerequisites

- Java 21 or higher
- Gradle 9.2.1 or higher (included via wrapper)

### Running Locally

1. **Start the backend server** (in another terminal):

   ```bash
   make dev-backend
   ```

   This starts the backend on port 3000.

2. **Start the frontend development server**:

   ```bash
   make dev-frontend
   ```

   This starts webpack-dev-server on port 3002 with:
   - Hot module replacement
   - API proxy to backend (port 3000)
   - Auto-reload on file changes

3. **Or run both together**:

   ```bash
   make dev
   ```

   This starts webpack-dev-server on port 3002 with:
   - Hot module replacement
   - API proxy to backend (port 3000)
   - Auto-reload on file changes

4. **Or run both together**:

   ```bash
   make dev-compose
   ```

5. **Open browser**: Navigate to http://localhost:3002/

### Default Credentials

Set these in your `.envrc` file (see `.envrc.sample`):

- Username: `admin`
- Password: `adminpass`

## Building

```bash
# Build the project
./gradlew :frontend:build

# Production build
./gradlew :frontend:jsBrowserProductionWebpack
```

The output will be in `build/dist/js/productionExecutable/`.

## Architecture

### Authentication Flow

1. User enters credentials on login page
2. `ApiClient.post()` sends credentials to `/api/login`
3. Backend validates and returns JWT token
4. Token is stored in `localStorage` via `Storage.setJwtToken()`
5. Token is automatically included in subsequent API requests
6. Navigation bar updates to show authenticated state

### State Management

- **Local State**: Component-level state using `mutableStateOf()`
- **Persistent State**: JWT token in localStorage
- **Navigation**: Client-side routing using window.location

### API Client

The `ApiClient` provides a type-safe HTTP client:

```kotlin
// POST request with serialization
val response = ApiClient.post<LoginResponse, LoginRequest>(
    "/api/login",
    LoginRequest(username, password)
)

when (response) {
    is ApiResponse.Success -> { /* handle success */ }
    is ApiResponse.Error -> { /* handle error */ }
    is ApiResponse.Exception -> { /* handle exception */ }
}
```

## Configuration

### Webpack Dev Server

The development server is configured in `webpack.config.d/dev-server.js`:

- **Port**: 3002
- **History API Fallback**: Enabled for client-side routing
- **Proxies**:
  - `/api` → `http://localhost:3000`
  - `/feed` → `http://localhost:3000`
  - `/files` → `http://localhost:3000`

### Gradle Build

Key dependencies in `build.gradle.kts`:

- `compose.html.core` - Compose for Web
- `kotlinx-serialization-json` - JSON serialization
- `kotlinx-coroutines-core` - Async operations
- `bulma` (npm) - CSS framework

## Troubleshooting

### Build Errors

If you see class file version errors:

```bash
# Make sure you're using Java 21
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
./gradlew :frontend:build
```

### Port Already in Use

If port 3002 is already in use:

```bash
# Find and kill the process
lsof -i :3002
kill -9 <PID>
```

### API Proxy Not Working

Make sure the backend is running on port 3000:

```bash
make dev-backend
```

Check backend logs for any errors.

## License

See LICENSE.txt in the root directory.
