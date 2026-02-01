# Best Practices Implementation Guide

This document outlines the best practices implemented in this codebase based on the agent skills for Arrow Resource, Arrow Typed Errors, and Compose State Hoisting.

## Arrow Resource Management

### ✅ Good Patterns Already Implemented

#### Database Connection Management
The database layer properly uses Arrow's `Resource` for managing database connections:

```kotlin
// backend/src/main/kotlin/socialpublish/backend/db/Database.kt
fun connect(config: DbConfig): Resource<Database> = resource {
    val dataSource = createDataSource(config).bind()
    val db = Database(dataSource)
    migrate(db).getOrElse { throw it }
    db
}
```

**Why this is good:**
- Automatically manages HikariCP connection pool lifecycle
- Ensures proper cleanup even on exceptions
- Composable with other resources

#### HTTP Client Management
Social API clients properly manage HTTP clients with Resource:

```kotlin
// Example from backend/src/main/kotlin/socialpublish/backend/clients/twitter/TwitterApiModule.kt
fun defaultHttpClient(): Resource<HttpClient> = resource {
    install(
        { HttpClient(CIO) { /* config */ } },
        { client, _ -> client.close() }
    )
}
```

**Why this is good:**
- HTTP clients are expensive resources
- Ensures clients are properly closed
- Prevents resource leaks

#### File Operations
Temporary file management uses Resource for automatic cleanup:

```kotlin
// backend/src/main/kotlin/socialpublish/backend/common/files.kt
fun createTempFileResource(prefix: String, suffix: String): Resource<File> = resource {
    val file = File.createTempFile(prefix, suffix).apply { deleteOnExit() }
    install({ file }, { f, _ -> f.delete() })
}
```

**Why this is good:**
- Temporary files are cleaned up reliably
- More reliable than `deleteOnExit()` alone
- Works correctly in async contexts

### 📋 Resource Management Checklist

When adding new features, ensure:
- [ ] HTTP clients are wrapped in `Resource`
- [ ] Database connections use the connection pool via `Resource`
- [ ] File I/O operations use `Resource` for cleanup
- [ ] Resources are composed with `.bind()` in `resourceScope` blocks
- [ ] Avoid manual try/finally blocks - use Resource instead

## Arrow Typed Errors

### ✅ Implemented Improvements

#### Typed Error ADTs
Created domain-specific error types in `backend/src/main/kotlin/socialpublish/backend/common/Errors.kt`:

```kotlin
sealed class AuthError(
    override val message: String,
    override val cause: Throwable? = null,
) : DomainError {
    data class InvalidToken(val details: String, override val cause: Throwable? = null) :
        AuthError("Invalid JWT token: $details", cause)

    data class InvalidCredentials(val details: String = "Invalid username or password") :
        AuthError(details)

    data class PasswordVerificationFailed(override val cause: Throwable) :
        AuthError("Password verification failed", cause)
}
```

**Why this is good:**
- Type-safe error handling
- Preserves error context and causes
- Enables exhaustive pattern matching
- Better than generic exceptions or nulls

#### Auth Module with Either
Updated `AuthModule` to return `Either<AuthError, T>`:

```kotlin
fun verifyToken(token: String): Either<AuthError, String> {
    return try {
        val jwt = verifier.verify(token)
        val username = jwt.getClaim("username").asString()
        if (username.isNullOrBlank()) {
            AuthError.InvalidToken("Missing username claim").left()
        } else {
            username.right()
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to verify JWT token" }
        AuthError.InvalidToken("Token verification failed", e).left()
    }
}
```

**Why this is good:**
- Explicit error channel in the type signature
- Callers know exactly what errors can occur
- No silent null returns
- Error information is preserved

#### Using Either in Routes
Route handlers use `fold` to handle Either results:

```kotlin
authModule.verifyPassword(request.password, config.passwordHash).fold(
    ifLeft = { error ->
        logger.warn { "Failed to verify password: ${error.message}" }
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
    },
    ifRight = { isPasswordValid ->
        if (request.username == config.username && isPasswordValid) {
            // Success path
        } else {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
        }
    }
)
```

**Why this is good:**
- Forces explicit error handling
- No exceptions to catch
- Clear separation of success and failure paths

### 📋 Typed Error Checklist

When writing error-prone code:
- [ ] Create sealed error types for your domain
- [ ] Return `Either<DomainError, T>` instead of nullable types
- [ ] Use `.fold()` or pattern matching to handle errors
- [ ] Avoid generic `catch (e: Exception)` blocks
- [ ] Never use `.getOrElse { throw it }` - defeats the purpose
- [ ] Preserve error context (causes, details)

### ⚠️ Anti-Patterns to Avoid

```kotlin
// ❌ DON'T: Generic exception catching with null return
fun parseUrl(url: String): ParsedUrl? {
    return try {
        // ...
    } catch (e: Exception) {
        null  // Lost all error information!
    }
}

// ✅ DO: Typed errors with Either
fun parseUrl(url: String): Either<ParseError, ParsedUrl> {
    return try {
        // ...
        parsedUrl.right()
    } catch (e: URISyntaxException) {
        ParseError.UrlParsingFailed(url, e).left()
    }
}

// ❌ DON'T: Defeating typed errors
someEither.getOrElse { throw it }

// ✅ DO: Handle errors properly
someEither.fold(
    ifLeft = { error -> /* handle error */ },
    ifRight = { value -> /* use value */ }
)
```

## Compose State Hoisting

### ✅ Implemented Improvements

#### NavBar State Hoisting
Moved navbar burger menu state from internal to parent-managed:

**Before:**
```kotlin
@Composable
fun NavBar(currentPath: String, onLogout: () -> Unit) {
    var navbarActive by remember { mutableStateOf(false) }  // ❌ Internal state
    // ...
}
```

**After:**
```kotlin
@Composable
fun NavBar(
    currentPath: String,
    isNavbarActive: Boolean,              // ✅ State from parent
    onNavbarToggle: (Boolean) -> Unit,    // ✅ Event callback
    onLogout: () -> Unit
) {
    // Component is now stateless
}
```

**Why this is good:**
- Parent controls when navbar closes (e.g., on route change)
- Easier to test
- State can be persisted or shared
- Follows unidirectional data flow

#### App-Level State Management
App component manages shared state and closes navbar on route changes:

```kotlin
@Composable
fun App() {
    var currentPath by remember { mutableStateOf(window.location.pathname) }
    var isNavbarActive by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener: (dynamic) -> Unit = { 
            currentPath = window.location.pathname
            isNavbarActive = false  // Close navbar on navigation
        }
        window.addEventListener("popstate", listener)
        onDispose { window.removeEventListener("popstate", listener) }
    }

    NavBar(
        currentPath = currentPath,
        isNavbarActive = isNavbarActive,
        onNavbarToggle = { isNavbarActive = it },
        onLogout = { /* ... */ }
    )
}
```

**Why this is good:**
- Centralized state management
- Clear data flow
- Easy to add new state-dependent behaviors

### ✅ Good Patterns Already Present

#### Stateless Form Inputs
Form input components properly hoist state:

```kotlin
@Composable
fun FormInputs(
    content: String,
    onContentChange: (String) -> Unit,  // ✅ Callback for changes
    // ... other state parameters
) {
    // Component receives state and communicates changes via callbacks
}
```

#### Stateless Image Upload
Image upload component is properly stateless:

```kotlin
@Composable
fun ImageUpload(
    images: List<ImageUploadItem>,
    onSelect: (File) -> Unit,    // ✅ Event callbacks
    onRemove: (Int) -> Unit       // ✅ Event callbacks
) {
    // No internal state, all managed by parent
}
```

### 📋 State Hoisting Checklist

For Compose components:
- [ ] State is passed as parameters, not created with `remember` internally
- [ ] Changes communicated via callback functions (onXxx patterns)
- [ ] Component doesn't maintain state that needs to be shared or persisted
- [ ] Parent controls component behavior through state
- [ ] Data flows down, events flow up

### ⚠️ When NOT to Hoist State

Some state should remain internal:
- **UI-only state**: Animation states, focus, scroll position
- **Transient state**: Hover states, temporary UI feedback
- **Performance**: Very frequently changing state that doesn't affect other components

```kotlin
// ✅ OK to keep internal - transient UI state
@Composable
fun Button() {
    var isHovered by remember { mutableStateOf(false) }
    // This doesn't need to be shared
}
```

## Summary of Changes

### Completed
1. ✅ Created typed error ADTs (`AuthError`, `FileError`, `ParseError`)
2. ✅ Updated `AuthModule` to use `Either<AuthError, T>`
3. ✅ Updated `AuthRoutes` to handle Either types correctly
4. ✅ Fixed all auth-related tests
5. ✅ Hoisted NavBar state to App component
6. ✅ Added automatic navbar close on route changes

### Documentation Value
- Existing good patterns are now documented for reference
- Future contributors can follow these patterns
- Tests serve as examples of correct Either usage
- Components demonstrate proper state hoisting

## Future Recommendations

1. **More Typed Errors**: Apply to FilesModule, LinkPreviewParser, etc.
2. **Resource Documentation**: Add KDoc explaining Resource usage patterns
3. **State Management**: Consider extracting AppState to separate file for complex apps
4. **Error Accumulation**: Use Arrow's `mapOrAccumulate` for validation with multiple errors

## References

- [Arrow Resource Documentation](https://arrow-kt.io/learn/coroutines/resource-safety/)
- [Arrow Typed Errors](https://arrow-kt.io/learn/typed-errors/working-with-typed-errors/)
- [Compose State Hoisting](https://developer.android.com/develop/ui/compose/state-hoisting)
