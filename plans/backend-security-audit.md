# Comprehensive Security & Code Quality Audit Report

## Social-Publish Backend (Scala 3)

---

Date: 2026-01-20

## Executive Summary

I conducted a thorough analysis of the `./backend/` Scala 3 codebase, examining security vulnerabilities, unimplemented features, and code quality issues. The analysis cross-referenced the backend implementation against frontend API expectations.

Overall Assessment: The backend has a solid functional foundation with good use of established libraries, but it lacks several critical web security controls required for production deployment. All tests pass (38/38), and core functionality is implemented, but LinkedIn integration is missing and several security gaps need immediate attention.

Security Score: 6.5/10

---

## 🔴 CRITICAL SECURITY VULNERABILITIES

1. No Rate Limiting ⚠️ URGENT

- Location: All endpoints in `Routes.scala`.
- Issue: No rate limiting implemented; vulnerable to brute force, DoS, and API abuse.
- Recommendation: Add rate limiting middleware (per-IP and per-user), exponential backoff for logins, and limits on file uploads.

2. No CSRF Protection

- Location: `Routes.scala`, all POST endpoints.
- Issue: State-changing operations accept requests without CSRF tokens; with cookie-based auth this is exploitable.
- Recommendation: Implement CSRF tokens or double-submit cookie, set `SameSite` cookie attribute.

3. Missing Critical Security Headers

- Location: `HttpServer.scala`, `Routes.scala`.
- Issue: No `X-Frame-Options`, `X-Content-Type-Options`, `Content-Security-Policy`, or HSTS header.
- Recommendation: Add middleware to set security headers.

4. Potential Path Traversal in Static File Serving

- Location: `Routes.scala` (resolveStaticPath implementation).
- Issue: Building paths with `mkString("/")` before normalization allows crafted segments (e.g., `..`) to escape `public` root under some conditions.
- Recommendation: Validate each path segment (reject `..`, separators, and disallow suspicious characters) and only then resolve.

5. JWT Security Weaknesses

- Location: `ServerConfig.scala`, `AuthMiddleware.scala`.
- Issues: No minimum secret strength enforcement, uses HS256 with 7-day expiration, no revocation or refresh mechanism.
- Recommendation: Enforce minimum secret length/entropy, consider RS256, introduce refresh tokens and revocation blacklist, shorten token lifetime.

6. Race Condition in File Locking

- Location: `FilesService.scala` (withLock implementation).
- Issue: Checking/modifying `locks` map is racy and can create multiple semaphores for same key.
- Recommendation: Use `Ref.modify` or atomic update pattern to ensure single semaphore per key.

7. Weak Random Number Generation for OAuth

- Location: `TwitterApi.scala`.
- Issue: Uses `scala.util.Random` for nonces — not cryptographically secure.
- Recommendation: Use `SecureRandom` to generate nonces.

---

## 🟠 HIGH SEVERITY ISSUES

8. Information Disclosure in Error Messages

- Location: Multiple (Twitter, Bluesky decoding, Routes file upload error responses).
- Issue: Error messages and exception contents are returned to clients or logged verbosely; this leaks internal details.
- Recommendation: Log full errors server-side, return sanitized client messages, use structured error codes.

9. Passwords Stored in Memory (Bluesky)

- Location: `BlueskyConfig`, `BlueskyApi`.
- Issue: Plain `String` credentials retained in memory for application life; vulnerable in heap dumps.
- Recommendation: Use zeroable char arrays or short-lived tokens, and avoid keeping plaintext creds.

10. SSRF Vulnerability Potential

- Location: Mastodon/Bluesky/Twitter config options.
- Issue: Accepts user-configurable service hosts/URLs without validation; attackers could point to internal services.
- Recommendation: Validate configured URLs against allowed domains, forbid localhost/internal IPs.

11. Missing File Size Limits

- Location: File upload endpoints.
- Issue: No maximum file size; can be abused to DoS storage and memory.
- Recommendation: Enforce a server-side max file size (e.g., 10MB), and validate before processing.

12. No Input Length Validation

- Location: `NewPostRequest` fields, alt text, tags, link.
- Issue: Unbounded inputs can cause storage or processing issues.
- Recommendation: Define and enforce reasonable maximum lengths and counts for inputs.

---

## 🟡 MEDIUM SEVERITY ISSUES

13. Cookie Security Issues

- Location: Cookie handling / extractCookieToken in `Routes.scala` and frontend storage.
- Issue: Frontend sets cookie via JavaScript (not HttpOnly); backend expects `access_token` cookie but there's no evidence cookies are set with `HttpOnly`, `Secure`, and `SameSite`.
- Recommendation: Backend should set `Set-Cookie` with `HttpOnly; Secure; SameSite=Strict; Path=/api`, and avoid exposing tokens to JS.

14. No Audit Logging

- Issue: No audit logs for authentication/authorization events and failed logins.
- Recommendation: Add structured audit logs for security events.

15. No Session Management

- Issue: JWT long-lived tokens with no revocation; no logout endpoint.
- Recommendation: Add token revocation (blacklist), refresh tokens, and `/api/logout`.

16. File Upload Security

- Location: `FilesService.scala`.
- Positives: Magic-byte MIME detection, image dimension extraction and processing.
- Issues: No antivirus scanning, no file size limit, original filename stored without sanitization (though UUID used for storage).
- Recommendation: Add size limit, sanitize filenames, virus scanning, and rate limit uploads.

17. HTML Cleanup Implementation is Naive

- Location: `TextUtils.scala` (convertHtml uses regex to remove tags)
- Issue: Regex-based HTML stripping is fragile and incomplete.
- Recommendation: Use a proper HTML parser (e.g., JSoup) to sanitize and extract text.

18. Test Coverage Gaps

- Status: Functional tests pass (38/38), but no security-focused tests (CSRF, rate limiting, cookie flags, file-size limits).
- Recommendation: Add tests covering negative paths and security behavior.

---

## 🚫 UNIMPLEMENTED FEATURES

1. LinkedIn Integration - NOT IMPLEMENTED

- Location: `Routes.scala` where `Target.LinkedIn` is mapped to `None` during `multiple` post handling.
- Impact: Frontend may present LinkedIn as an option; backend will ignore it silently.
- Recommendation: Either implement LinkedIn OAuth & publish flow, or remove/hide LinkedIn option in the frontend until implemented.

2. Frontend/Backend API Contract Discrepancies

- Twitter `status` response returns `createdAt: Long` (epoch) while frontend expects optional ISO datetime string.
- Recommendation: Either normalize to ISO strings or update frontend parsing to accept epoch.

---

## ✅ POSITIVE SECURITY FINDINGS

1. ✅ SQL Injection Prevention: Excellent use of Doobie parameterized queries throughout
2. ✅ Password Hashing: BCrypt used for password storage (AuthMiddleware.scala:76)
3. ✅ MIME Type Validation: Magic byte checking prevents file type spoofing (FilesService.scala:273-292)
4. ✅ Path Normalization: Attempt to prevent path traversal (though needs improvement)
5. ✅ XML Escaping: Proper escaping in RSS feed generation
6. ✅ HTTPS for External APIs: Default configurations use HTTPS endpoints
7. ✅ No Hardcoded Secrets: All secrets from environment variables
8. ✅ OAuth 1.0a Implementation: Proper HMAC-SHA1 signing for Twitter
9. ✅ File Deduplication: Content-based addressing prevents storage abuse
10. ✅ Content Length Validation: 1-1000 character limit enforced

---

## 🎯 PRIORITIZED REMEDIATION PLAN


**Immediate (Production Blockers)**

- Add rate limiting middleware.
- Implement CSRF protection and use `SameSite` cookies.
- Add security headers (CSP, HSTS, X-Frame-Options, X-Content-Type-Options).
- Fix race condition in `FilesService.withLock`.
- Replace `scala.util.Random` usage with `SecureRandom` for nonces.
- Enforce server-side upload size limits.
- Remove or implement LinkedIn integration.

**Short Term (1–2 weeks)**

- Sanitize error messages returned to clients; log full details server-side.
- Add logout/revocation and refresh token support.
- Add input length validation and constrain arrays (images/tags).
- Add audit logging for authentication/authorization.
- Add SSRF protection for configurable external URLs.

**Medium Term (1 month)**

- Consider RS256 JWT usage and secret rotation.
- Add virus scanning for uploads.
- Add security tests and CI checks for headers/CSRF.
- Implement CSP and refine CSP rules.

**Long Term**

- Penetration testing and automated security scans.
- Harden secrets management and rotation.

---

## Final Notes

- Functional status: tests pass (38/38).
- The application is not production-ready until the critical security issues (rate limiting, CSRF, headers, cookie handling) are remediated.
- Estimated effort to address critical/high items: 2–3 developer weeks.

If you want, I can:

- Implement a first pass of the highest priority fixes (rate limiting + security headers + CSRF),
- Add server-side file size validation, and
- Add a `plans/` checklist with actionable git-friendly tasks.

---

_Generated by automated code analysis on repository: rewrite-backend-with-scala-3_
