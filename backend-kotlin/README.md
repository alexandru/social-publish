# Social Publish - Kotlin Backend

This directory contains the Kotlin/JVM rewrite of the social-publish backend.

## Status

**Completed:**
- ✅ Gradle build configuration with all required dependencies
- ✅ Complete database layer (JDBI + SQLite) with migrations
- ✅ Domain models with Kotlinx Serialization
- ✅ Basic Ktor HTTP server
- ✅ CLI argument parsing with Clikt
- ✅ Test infrastructure with working database tests
- ✅ Application compiles and runs successfully

**In Progress/TODO:**
- ⏳ Social media API integrations (Bluesky, Mastodon, Twitter)
- ⏳ Authentication/Authorization (JWT, BasicAuth)
- ⏳ File upload and image processing (Scrimage)
- ⏳ RSS feed generation
- ⏳ Static file serving
- ⏳ Complete HTTP route implementations
- ⏳ Comprehensive test coverage

## Building

```bash
./gradlew build
```

## Running

```bash
# Using Gradle
DB_PATH=/tmp/social-publish.db ./gradlew run

# Or run the built JAR directly
DB_PATH=/tmp/social-publish.db java -jar build/libs/social-publish-1.0.0.jar
```

## Testing

```bash
./gradlew test
```

## Configuration

All configuration can be provided via environment variables or command-line arguments:

- `DB_PATH` - Path to SQLite database file
- `HTTP_PORT` - Port to listen on (default: 3000)
- `BASE_URL` - Public URL of this server
- `SERVER_AUTH_USERNAME` - Basic auth username
- `SERVER_AUTH_PASSWORD` - Basic auth password
- `JWT_SECRET` - JWT secret for authentication
- `BSKY_SERVICE` - Bluesky service URL (default: https://bsky.social)
- `BSKY_USERNAME` - Bluesky username
- `BSKY_PASSWORD` - Bluesky password
- `MASTODON_HOST` - Mastodon host URL
- `MASTODON_ACCESS_TOKEN` - Mastodon access token
- `TWITTER_OAUTH1_CONSUMER_KEY` - Twitter OAuth1 consumer key
- `TWITTER_OAUTH1_CONSUMER_SECRET` - Twitter OAuth1 consumer secret
- `UPLOADED_FILES_PATH` - Directory for uploaded files

## Architecture

The Kotlin backend follows functional programming principles where appropriate:

- **Arrow** for typed errors (`Either<ApiError, T>`)
- **JDBI** for database access (type-safe SQL)
- **Ktor** for HTTP server (coroutine-based)
- **Kotlinx Serialization** for JSON handling
- **Clikt** for CLI parsing

## Project Structure

```
src/
├── main/kotlin/com/alexn/socialpublish/
│   ├── Main.kt                 # Application entry point
│   ├── config/
│   │   └── AppConfig.kt        # Configuration and CLI parsing
│   ├── db/
│   │   ├── Database.kt         # Database connection and migrations
│   │   ├── DocumentsDatabase.kt # Generic document storage
│   │   ├── PostsDatabase.kt    # Posts storage
│   │   └── FilesDatabase.kt    # File metadata storage
│   ├── models/
│   │   ├── ApiError.kt         # Typed error models
│   │   └── Posts.kt            # Post models
│   └── server/
│       └── Server.kt           # Ktor HTTP server setup
└── test/kotlin/com/alexn/socialpublish/
    └── db/
        └── PostsDatabaseTest.kt # Database tests
```

## Dependencies

Key libraries used:

- **Kotlin 2.0.21** - Latest Kotlin version
- **Ktor 3.0.1** - HTTP server and client
- **Arrow 2.0.0** - Functional programming toolkit
- **JDBI 3.45.4** - Database access
- **Kotlinx Serialization 1.7.3** - JSON serialization
- **Clikt 5.0.1** - CLI parsing
- **Scrimage 4.3.2** - Image processing
- **Logback + kotlin-logging** - Logging

## Next Steps

To complete the backend rewrite:

1. **Implement Bluesky API client** using Ktor HTTP client (AT Protocol)
2. **Implement Mastodon API client** using Ktor HTTP client
3. **Implement Twitter API client** with OAuth 1.0a
4. **Add JWT authentication** using Ktor's auth plugin
5. **Add file upload handling** with multipart/form-data support
6. **Add image processing** using Scrimage for resizing
7. **Implement RSS feed generation** using ROME library
8. **Add static file serving** for frontend
9. **Write comprehensive tests** for all modules
10. **Update Dockerfile** to use JVM base image
11. **Update CI/CD workflows** for Gradle builds
