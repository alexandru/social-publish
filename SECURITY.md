# Security Audit Report

**Date:** January 25, 2026  
**Scope:** DB utilities, modules, and endpoint protection

## Summary

A comprehensive security review was conducted covering:
- Database layer (DocumentsDatabase, FilesDatabase, PostsDatabase)
- Authentication and authorization (AuthModule)
- API endpoint protection
- File handling (FilesModule)
- Input validation and error handling

**Test Coverage Added:** 34 new tests
- 13 tests for DocumentsDatabase
- 11 tests for FilesDatabase
- 10 tests for endpoint security

## Critical Findings

### 🔴 CRITICAL: Public File Download Endpoint

**Location:** `GET /files/{uuid}`  
**Severity:** High  
**Status:** Identified

**Issue:**
The file download endpoint is publicly accessible without authentication. Anyone who knows or can guess a file UUID can download it.

**Current Implementation:**
```kotlin
// In Server.kt (outside authenticate block)
get("/files/{uuid}") { filesModule.getFile(call) }
```

**Risk:**
- Unauthorized access to uploaded images and files
- Files uploaded with expectation of privacy could be exposed
- UUID v5 generation is deterministic based on file hash and metadata, making UUIDs potentially guessable

**Recommendations:**

**Option 1: Add Authentication (Recommended for private files)**
```kotlin
authenticate("auth-jwt") {
    get("/files/{uuid}") { filesModule.getFile(call) }
}
```

**Option 2: Signed URLs with Expiration (Recommended for shared files)**
Implement time-limited signed URLs that expire after a set period (e.g., 1 hour).

**Option 3: Document as Intentional (If files are meant to be public)**
If files are intentionally public (like a CDN), document this design decision and ensure users understand uploaded files are publicly accessible.

### 🟡 MEDIUM: Long-Lived JWT Tokens

**Location:** `AuthModule.kt`  
**Severity:** Medium  
**Status:** Identified

**Issue:**
JWT tokens have a 6-month expiration period (180 days). If a token is compromised, it remains valid for an extended period.

**Current Implementation:**
```kotlin
private const val JWT_EXPIRATION_MILLIS = 180L * 24 * 60 * 60 * 1000
```

**Risk:**
- Compromised tokens can be used for months
- No mechanism to revoke tokens (stateless JWT)
- Increased window of opportunity for attackers

**Recommendations:**
1. Reduce token expiration to 24 hours or less
2. Implement refresh tokens for seamless re-authentication
3. Consider maintaining a token blacklist for critical security events
4. Add token versioning to invalidate all tokens on password change

### 🟡 MEDIUM: Limited Rate Limiting

**Location:** `Server.kt`  
**Severity:** Medium  
**Status:** Identified

**Issue:**
Rate limiting is only applied to the login endpoint. Resource-intensive operations like file uploads and post creation have no rate limiting.

**Current Implementation:**
```kotlin
install(RateLimit) {
    register(RateLimitName("login")) { 
        rateLimiter(limit = 20, refillPeriod = 5.minutes) 
    }
}
```

**Risk:**
- Denial of Service through excessive file uploads
- API abuse without cost
- Resource exhaustion

**Recommendations:**
Add rate limiting to:
- `POST /api/files/upload` - e.g., 10 uploads per minute
- `POST /api/*/post` endpoints - e.g., 30 posts per minute
- `GET /rss*` endpoints - e.g., 60 requests per minute

Example:
```kotlin
install(RateLimit) {
    register(RateLimitName("login")) { 
        rateLimiter(limit = 20, refillPeriod = 5.minutes) 
    }
    register(RateLimitName("upload")) { 
        rateLimiter(limit = 10, refillPeriod = 1.minutes) 
    }
    register(RateLimitName("api")) { 
        rateLimiter(limit = 30, refillPeriod = 1.minutes) 
    }
}
```

### 🟡 MEDIUM: No File Size Validation

**Location:** `FilesModule.uploadFile()`  
**Severity:** Medium  
**Status:** Identified

**Issue:**
File uploads are read into memory without size validation. Large files could cause memory exhaustion.

**Current Implementation:**
```kotlin
fileBytes = withContext(Dispatchers.LOOM) {
    part.provider().readRemaining().readByteArray()
}
```

**Risk:**
- Memory exhaustion through large file uploads
- Denial of Service
- Increased storage costs

**Recommendations:**
1. Add max file size check before reading into memory (e.g., 10 MB)
2. Stream large files to disk instead of reading into memory
3. Return clear error message when file size limit is exceeded

Example:
```kotlin
val maxFileSizeBytes = 10 * 1024 * 1024 // 10 MB
val contentLength = part.headers[HttpHeaders.ContentLength]?.toLongOrNull()

if (contentLength != null && contentLength > maxFileSizeBytes) {
    return ValidationError(
        status = 413,
        errorMessage = "File too large. Maximum size is 10 MB.",
        module = "files"
    ).left()
}
```

## Security Best Practices Confirmed ✅

### SQL Injection Protection
- **Status:** Excellent
- All database queries use parameterized statements
- No string concatenation of SQL queries
- Properly using prepared statements throughout

Example:
```kotlin
query("SELECT * FROM documents WHERE search_key = ?") {
    setString(1, searchKey)
    // ... safe from SQL injection
}
```

### Password Security
- **Status:** Excellent
- BCrypt hashing with 12 rounds (configurable)
- Passwords never stored in plain text
- Verification uses constant-time comparison

```kotlin
fun hashPassword(password: String, rounds: Int = 12): String {
    return String(
        FavreBCrypt.withDefaults().hash(rounds, password.toCharArray()),
        Charsets.UTF_8,
    )
}
```

### JWT Token Security
- **Status:** Good
- HMAC256 signing algorithm
- Secret stored in environment variable
- Tokens include expiration claims
- Subject and custom claims validated

### Error Handling
- **Status:** Good
- 500 errors don't expose internal details
- Generic error messages to clients
- Detailed logging for debugging
- Exception handler prevents information leakage

```kotlin
install(StatusPages) {
    exception<Throwable> { call, cause ->
        logger.error(cause) { "Unhandled exception" }
        call.respondText(
            text = "500: Internal Server Error",
            status = HttpStatusCode.InternalServerError,
        )
    }
}
```

### CORS Configuration
- **Status:** Good
- Restricted to configured base URL
- Not wildcard allowing all origins
- Credentials allowed only for same origin

### Authentication Implementation
- **Status:** Good
- Multiple token extraction methods (header, query, cookie)
- Proper validation of JWT claims
- Challenge handler returns appropriate status codes

## Testing Coverage

### Database Layer Tests
- ✅ DocumentsDatabase: 13 tests
  - Create/update operations
  - Search by key and UUID
  - Tag management
  - Ordering and retrieval
  - Edge cases (non-existent records, empty results)

- ✅ FilesDatabase: 11 tests
  - File creation and deduplication
  - UUID v5 generation (deterministic)
  - Retrieval by UUID
  - Null handling for optional fields

### Endpoint Security Tests
- ✅ EndpointSecurityTest: 10 tests
  - Protected endpoints reject unauthenticated requests
  - Invalid tokens rejected
  - Valid tokens accepted
  - Token extraction from query parameters
  - JWT claims validation
  - Token tampering detection

## Recommendations Summary

### Immediate Actions (High Priority)
1. **Fix file download endpoint** - Add authentication or document as intentional public access
2. **Add file size limits** - Prevent memory exhaustion
3. **Add rate limiting** - Protect resource-intensive endpoints

### Short-term Actions (Medium Priority)
1. **Reduce JWT expiration** - Implement shorter-lived tokens with refresh
2. **Add monitoring** - Track failed authentication attempts
3. **Security headers** - Add CSP, X-Frame-Options, etc.

### Long-term Considerations
1. **Token revocation** - Implement blacklist or versioning
2. **Audit logging** - Track all authentication and authorization events
3. **Penetration testing** - Professional security audit
4. **Dependency scanning** - Regular CVE checks

## Test Execution

All 34 new tests pass successfully:

```bash
./gradlew :backend:test --tests DocumentsDatabaseTest
./gradlew :backend:test --tests FilesDatabaseTest
./gradlew :backend:test --tests EndpointSecurityTest
```

**Result:** BUILD SUCCESSFUL - All tests passing ✅

## Conclusion

The application demonstrates strong security fundamentals with proper use of BCrypt password hashing, parameterized SQL queries, and JWT authentication. The main areas for improvement are:

1. **File access control** (critical)
2. **Token lifecycle management** (medium)
3. **Rate limiting coverage** (medium)
4. **Input validation** (medium)

Addressing these issues will significantly improve the security posture of the application.
