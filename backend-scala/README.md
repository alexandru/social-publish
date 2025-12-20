# Social Publish - Scala 3 Backend

This directory contains the Scala 3 rewrite of the social-publish backend server.

## Overview

The backend has been rewritten from Node.js/TypeScript to Scala 3 using functional programming principles and popular Typelevel libraries.

## Technology Stack

- **Scala 3.3.4** - Modern Scala with improved syntax and type system
- **Http4s 0.23** - Functional HTTP server and client
- **Circe 0.14** - JSON parsing and serialization
- **Doobie 1.0** - Functional JDBC layer for SQLite
- **Cats Effect 3.5** - Effect system for managing I/O
- **Decline 2.4** - Command-line argument parsing
- **Log4cats 2.7** - Logging with Cats Effect
- **JWT Scala 10.0** - JSON Web Token support

## Project Structure

```
backend-scala/
├── build.sbt                       # SBT build configuration
├── project/
│   ├── build.properties           # SBT version
│   └── plugins.sbt                # SBT plugins
└── src/main/
    ├── resources/
    │   └── logback.xml            # Logging configuration
    └── scala/socialpublish/
        ├── Main.scala              # Application entry point
        ├── models/
        │   ├── Domain.scala        # Core domain models (Post, File, Target, etc.)
        │   └── Errors.scala        # Error ADTs and Result type
        ├── config/
        │   └── AppConfig.scala     # CLI configuration using Decline
        ├── db/
        │   ├── DocumentsDatabase.scala  # Generic document storage
        │   ├── PostsDatabase.scala      # Posts database layer
        │   └── FilesDatabase.scala      # Files metadata database
        ├── api/
        │   ├── BlueskyApi.scala    # Bluesky AT Protocol client
        │   ├── MastodonApi.scala   # Mastodon API client
        │   └── TwitterApi.scala    # Twitter OAuth1 client (stub)
        ├── services/
        │   └── FilesService.scala  # File upload and image processing
        ├── http/
        │   └── Routes.scala        # HTTP routes and server (needs completion)
        └── utils/
            └── TextUtils.scala     # Text utilities (HTML conversion, hashing)
```

## Key Features Implemented

### Domain Models
- **Enums for Target types** (Bluesky, Mastodon, Twitter, LinkedIn)
- **Type-safe models** for Post, File, Document with proper Circe codecs
- **Custom error ADT** with Result type for functional error handling

### Database Layer
- **Doobie-based** database access with proper Resource management
- **SQLite** database with automatic migrations
- **Generic document storage** pattern for flexible data persistence
- **Type-safe SQL** queries with compile-time checking

### API Clients
- **Bluesky**: Full AT Protocol implementation with:
  - Session management
  - Blob/image upload
  - Rich text post creation with facets (links detection)
  - Media embeds

- **Mastodon**: Complete implementation with:
  - Media upload with alt text
  - Status posting
  - Multipart form data support

- **Twitter**: OAuth1 stub (requires completion)

### Services
- **File Service**: Image upload, processing, and storage
- **Authentication**: JWT and Basic Auth support
- **CLI**: Comprehensive configuration using Decline with environment variable fallbacks

## Building and Running

### Prerequisites
- Java 17 or later
- SBT 1.10.7

### Build
```bash
cd backend-scala
sbt compile
```

### Create Fat JAR
```bash
sbt assembly
```

### Run
```bash
sbt run \
  --db-path /var/lib/social-publish/sqlite3.db \
  --http-port 3000 \
  --base-url https://your-domain.com \
  # ... additional config
```

Or use environment variables:
```bash
export DB_PATH=/var/lib/social-publish/sqlite3.db
export HTTP_PORT=3000
export BASE_URL=https://your-domain.com
export SERVER_AUTH_USERNAME=admin
export SERVER_AUTH_PASSWORD=secret
export JWT_SECRET=your-jwt-secret
export BSKY_USERNAME=your-username
export BSKY_PASSWORD=your-password
export MASTODON_HOST=https://mastodon.social
export MASTODON_ACCESS_TOKEN=your-token
export TWITTER_OAUTH1_CONSUMER_KEY=your-key
export TWITTER_OAUTH1_CONSUMER_SECRET=your-secret
export UPLOADED_FILES_PATH=/var/lib/social-publish/uploads

sbt run
```

## API Endpoints

### Public Endpoints
- `GET /ping` - Health check
- `POST /api/login` - Login with Basic Auth, returns JWT
- `GET /rss` - RSS feed of all posts
- `GET /rss/target/:target` - RSS feed filtered by target
- `GET /rss/:uuid` - Individual RSS item
- `GET /files/:uuid` - Serve uploaded file

### Authenticated Endpoints (Bearer token required)
- `POST /api/bluesky/post` - Post to Bluesky
- `POST /api/mastodon/post` - Post to Mastodon
- `POST /api/twitter/post` - Post to Twitter
- `POST /api/multiple/post` - Post to multiple networks
- `POST /api/rss/post` - Create RSS item
- `GET /api/protected` - Test authentication
- `GET /api/twitter/authorize` - Start Twitter OAuth flow
- `GET /api/twitter/callback` - Twitter OAuth callback
- `GET /api/twitter/status` - Twitter connection status

## Status

### Completed
- ✅ Project structure and build configuration
- ✅ Core domain models with proper type safety
- ✅ Database layer with Doobie and SQLite
- ✅ Bluesky API client (full implementation)
- ✅ Mastodon API client (full implementation)
- ✅ File service with image processing
- ✅ CLI configuration with Decline
- ✅ Authentication (JWT and Basic Auth)
- ✅ Main application wiring

### In Progress / Needs Completion
- ⚠️ HTTP Routes compilation errors (type mismatches)
- ⚠️ Twitter OAuth1 full implementation
- ⚠️ File upload HTTP routes
- ⚠️ Unit and integration tests
- ⚠️ Docker deployment configuration

## Testing

Tests can be run with:
```bash
sbt test
```

## Functional Programming Principles Used

1. **Pure Functions**: All business logic is pure and side-effect free
2. **Effect System**: IO operations use Cats Effect's IO monad
3. **Error Handling**: Custom error ADTs with EitherT for composable error handling
4. **Resource Management**: Proper resource acquisition and release using Resource
5. **Type Safety**: Leveraging Scala 3's improved type system and given/using
6. **Immutability**: All data structures are immutable
7. **Composition**: Small, composable functions combined to build complex behavior

## Migration from TypeScript

The rewrite maintains feature parity with the TypeScript version while improving:
- **Type safety**: Compile-time guarantees vs runtime checks
- **Concurrency**: Built-in effect system for safe concurrent operations
- **Performance**: JVM performance and optimization
- **Composability**: Functional abstractions for better code reuse
- **Maintainability**: Strong types and functional patterns reduce bugs

## License

MIT (same as original project)
