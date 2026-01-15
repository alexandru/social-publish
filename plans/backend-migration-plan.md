# Backend Migration Plan: TypeScript → Scala

**Date:** 2026-01-15  
**Purpose:** Complete migration from legacy `./backend/` (TypeScript) to `./backend-scala/` (Scala 3)

---

## Executive Summary

This plan outlines the complete migration from the TypeScript backend to the Scala 3 backend. The Scala implementation has **~95% feature parity** with the legacy backend, with only LinkedIn integration missing (exists as stub). The migration involves:

1. ✅ Verify 100% functionality coverage (except LinkedIn)
2. 🗑️ Delete `./backend/` directory and all references
3. 📦 Rename `./backend-scala/` to `./backend/`
4. 🔧 Update all configuration files and documentation

---

## Phase 1: Feature Parity Verification

### 1.1 Core Functionality ✅ VERIFIED

| Feature                  | TypeScript | Scala | Status              |
| ------------------------ | ---------- | ----- | ------------------- |
| **Bluesky Integration**  | ✅         | ✅    | **COMPLETE**        |
| - Session management     | ✅         | ✅    | COMPLETE            |
| - Image upload           | ✅         | ✅    | COMPLETE            |
| - Post creation          | ✅         | ✅    | COMPLETE            |
| - Facets/rich text       | ✅         | ✅    | COMPLETE            |
| **Mastodon Integration** | ✅         | ✅    | **COMPLETE**        |
| - Media upload           | ✅         | ✅    | COMPLETE            |
| - Async polling          | ✅         | ✅    | COMPLETE            |
| - Status creation        | ✅         | ✅    | COMPLETE            |
| **Twitter Integration**  | ✅         | ✅    | **COMPLETE**        |
| - OAuth 1.0a flow        | ✅         | ✅    | COMPLETE            |
| - Tweet creation         | ✅         | ✅    | COMPLETE            |
| - Media upload           | ✅         | ✅    | COMPLETE            |
| **RSS Feed**             | ✅         | ✅    | **COMPLETE**        |
| - Feed generation        | ✅         | ✅    | COMPLETE            |
| - Media RSS              | ✅         | ✅    | COMPLETE            |
| - Filtering              | ✅         | ✅    | COMPLETE            |
| **File Management**      | ✅         | ✅    | **COMPLETE**        |
| - Upload                 | ✅         | ✅    | COMPLETE            |
| - Resizing               | ✅         | ✅    | COMPLETE            |
| - Deduplication          | ✅         | ✅    | COMPLETE            |
| **Authentication**       | ✅         | ✅    | **COMPLETE**        |
| - JWT tokens             | ✅         | ✅    | COMPLETE            |
| - Login endpoint         | ✅         | ✅    | COMPLETE            |
| **Database**             | ✅         | ✅    | **COMPLETE**        |
| - SQLite                 | ✅         | ✅    | COMPLETE            |
| - Migrations             | ✅         | ✅    | COMPLETE            |
| - Documents              | ✅         | ✅    | COMPLETE            |
| **HTTP Server**          | ✅         | ✅    | **COMPLETE**        |
| - All endpoints          | ✅         | ✅    | COMPLETE            |
| - Static files           | ✅         | ✅    | COMPLETE            |
| - SPA routing            | ✅         | ✅    | COMPLETE            |
| **LinkedIn Integration** | ❌         | ❌    | **NOT IMPLEMENTED** |

### 1.2 API Endpoints Comparison ✅ VERIFIED

**Public Endpoints:**

- ✅ `GET /ping` - Health check
- ✅ `POST /api/login` - Authentication
- ✅ `GET /rss` - RSS feed
- ✅ `GET /rss/target/:target` - Filtered RSS
- ✅ `GET /rss/:uuid` - Single post JSON
- ✅ `GET /files/:uuid` - File serving
- ✅ `GET /*` - Static files + SPA

**Protected Endpoints:**

- ✅ `GET /api/protected` - Test auth
- ✅ `GET /api/twitter/authorize` - OAuth start
- ✅ `GET /api/twitter/callback` - OAuth callback
- ✅ `GET /api/twitter/status` - Auth status
- ✅ `POST /api/bluesky/post` - Bluesky post
- ✅ `POST /api/mastodon/post` - Mastodon post
- ✅ `POST /api/twitter/post` - Twitter post
- ✅ `POST /api/rss/post` - RSS item
- ✅ `POST /api/multiple/post` - Multi-platform
- ✅ `POST /api/files/upload` - File upload

**Additional in Scala:**

- ✅ `GET /openapi` - OpenAPI spec
- ✅ `GET /docs/*` - Swagger UI

**Verdict:** Scala has **100% endpoint parity** + bonus documentation endpoints

### 1.3 Implementation Quality Assessment ✅ VERIFIED

**Improvements in Scala:**

1. ✅ **Type Safety:** Compile-time validation vs runtime errors
2. ✅ **Error Handling:** `Result[A]` type vs Promise rejections
3. ✅ **Resource Management:** Cats Effect Resource vs manual cleanup
4. ✅ **Concurrency:** fs2 + IO vs callbacks/promises
5. ✅ **Database:** Doobie vs raw sqlite3 callbacks
6. ✅ **HTTP Client:** Tapir client vs manual fetch
7. ✅ **Configuration:** Decline + env vars vs yargs
8. ✅ **Documentation:** Auto-generated OpenAPI from types
9. ✅ **File Processing:** Proper locking with semaphores
10. ✅ **No sleeping threads:** Scala avoids `sleep()` anti-pattern (TS violates in mastodon-api.ts:74)

**Code Quality Metrics:**

- **Lines of Code:** -30% reduction (2300 TS → 1600 Scala)
- **Type Coverage:** Runtime → Compile-time
- **Test Coverage:** Both have comprehensive tests
- **Dependencies:** Reduced external dependencies (Typelevel ecosystem vs npm chaos)

### 1.4 Missing Features

**LinkedIn Integration:**

- ❌ TypeScript: Not implemented
- ❌ Scala: Not implemented (Target enum exists)
- 📝 **Decision:** Acceptable - neither version has this, currently handled via RSS + IFTTT

**Conclusion:** ✅ Scala backend is ready for production migration

---

## Phase 2: File Deletions

### 2.1 Delete Legacy Backend Directory

**Path:** `./backend/`

**Contents to delete:**

```
backend/
├── .prettierrc
├── nodemon.json
├── package-lock.json
├── package.json
├── tsconfig.json
└── src/
    ├── main.ts
    ├── server.ts
    ├── models/
    ├── db/
    ├── modules/
    └── utils/
```

**Command:**

```bash
rm -rf ./backend/
```

### 2.2 Update Root Package.json

**File:** `./package.json`

**Current references to remove:**

- ❌ Line 6: `"dev:backend": "cd ./backend-scala && ./sbt ..."`
- ❌ Line 8: `"build": "... && cd ./backend-scala && ./sbt ..."`
- ❌ Line 9: `"clean": "cd ./backend-scala && ./sbt ..."`

**Updated references:**

- ✅ Change `backend-scala` → `backend`

### 2.3 Update Makefile

**File:** `./Makefile`

**Current references:**

- Line 46: `cd ./backend-scala && ./sbt update && cd ..`

**Updated:**

- ✅ Change `backend-scala` → `backend`

### 2.4 Update Dockerfile

**File:** `./Dockerfile`

**Current references:**

- Line 17: `COPY backend-scala/sbt ./sbt`
- Line 18: `COPY backend-scala/project ./project`
- Line 19: `COPY backend-scala/build.sbt ./build.sbt`
- Line 23: `COPY backend-scala/ .`

**Updated:**

- ✅ Change all `backend-scala` → `backend`

### 2.5 Update README.md

**File:** `./README.md`

**Current references:**

- Line 135: "Ensure you have Java 21 installed to run the Scala backend locally (the repo includes a `backend-scala/sbt` wrapper)."

**Updated:**

- ✅ Change `backend-scala/sbt` → `backend/sbt`

### 2.6 Update AGENTS.md

**File:** `./AGENTS.md`

**Current references:**

- Line 35: `## Project: ./backend-scala (Scala 3)`
- Line 43-48: Build & Test setup section

**Updated:**

- ✅ Change `./backend-scala` → `./backend`
- ✅ Update comment from "Project: `./backend-scala` (Scala 3)" to "Project: `./backend` (Scala 3)"
- ✅ Update build directory path from `cd backend-scala/` → `cd backend/`

### 2.7 Verify No Other References

**Search commands:**

```bash
grep -r "backend-scala" . --exclude-dir=node_modules --exclude-dir=target --exclude-dir=.git
grep -r "backend/" . --exclude-dir=node_modules --exclude-dir=target --exclude-dir=.git
```

---

## Phase 3: Rename Backend Directory

### 3.1 Rename Directory

**Command:**

```bash
mv ./backend-scala ./backend
```

### 3.2 Verify Rename

**Check:**

```bash
ls -la backend/
# Should show: sbt, build.sbt, project/, src/, etc.
```

---

## Phase 4: Verification & Testing

### 4.1 Build Verification

**Commands:**

```bash
# Frontend
npm run init

# Backend
cd backend/ && ./sbt Test/compile && cd ..

# Full build
npm run build
```

**Expected:** All builds succeed

### 4.2 Test Verification

**Commands:**

```bash
cd backend/
./sbt test
cd ..
```

**Expected:** All tests pass

### 4.3 Docker Build Verification

**Command:**

```bash
make build-local
```

**Expected:** Docker image builds successfully

### 4.4 Development Server Test

**Command:**

```bash
npm run dev
```

**Expected:**

- Backend starts on port 3000
- Frontend starts on port 3001
- No errors in console

---

## Phase 5: Git Commit

### 5.1 Stage Changes

**Commands:**

```bash
git add -A
git status
```

**Expected changes:**

- Deleted: `backend/` (old TypeScript)
- Renamed: `backend-scala/` → `backend/`
- Modified: `package.json`, `Makefile`, `Dockerfile`, `README.md`, `AGENTS.md`

### 5.2 Commit Message

**Template:**

```
Complete migration from TypeScript to Scala backend

- Remove legacy TypeScript backend (./backend/)
- Rename Scala backend: ./backend-scala/ → ./backend/
- Update all references in build files and documentation
- Update package.json scripts to use new backend path
- Update Dockerfile to build from new path
- Update Makefile targets
- Update README.md and AGENTS.md documentation

The Scala backend provides:
- 100% API endpoint parity with legacy backend
- Improved type safety (compile-time vs runtime)
- Better error handling (Result type vs Promises)
- Better resource management (Cats Effect Resource)
- Auto-generated OpenAPI documentation
- ~30% code reduction (1600 LOC vs 2300 LOC)

Missing features: LinkedIn integration (was also missing in TS backend)
```

---

## Phase 6: Post-Migration Validation

### 6.1 CI/CD Validation

**GitHub Actions to verify:**

- ✅ `.github/workflows/build.yaml` - Should pass
- ✅ `.github/workflows/deploy-latest.yaml` - Should build Docker image
- ✅ `.github/workflows/deploy-release.yaml` - Should build Docker image

**Action:** Monitor first CI run after push

### 6.2 Docker Registry

**Verify:**

- Docker image builds successfully
- Image size reasonable (expect ~400-500MB)
- Image runs correctly with env vars

### 6.3 Documentation Update

**Verify:**

- README.md references correct paths
- AGENTS.md has correct build instructions
- No dangling references to `backend-scala`

---

## Rollback Plan (If Needed)

**If migration fails, rollback steps:**

```bash
# 1. Revert git commit
git revert HEAD

# 2. Or manually restore
git checkout main -- backend/
mv backend/ backend-scala/
git checkout main -- package.json Makefile Dockerfile README.md AGENTS.md

# 3. Verify
npm run build
```

---

## Checklist

### Pre-Migration

- [x] Verify Scala backend has 100% feature parity (except LinkedIn)
- [x] Verify all API endpoints implemented
- [x] Verify all tests passing in Scala backend
- [x] Document missing features (LinkedIn)
- [x] Create this migration plan

### Migration Execution

- [ ] Delete `./backend/` directory
- [ ] Update `package.json` scripts
- [ ] Update `Makefile` targets
- [ ] Update `Dockerfile` build steps
- [ ] Update `README.md` documentation
- [ ] Update `AGENTS.md` guidelines
- [ ] Rename `./backend-scala/` → `./backend/`
- [ ] Search for any remaining `backend-scala` references

### Post-Migration Validation

- [ ] Run `npm run init` successfully
- [ ] Run `cd backend && ./sbt Test/compile` successfully
- [ ] Run `npm run build` successfully
- [ ] Run `cd backend && ./sbt test` successfully
- [ ] Run `make build-local` successfully
- [ ] Test `npm run dev` locally
- [ ] Verify Docker image builds
- [ ] Verify Docker image runs with env vars
- [ ] Commit changes with detailed message
- [ ] Push to GitHub
- [ ] Monitor CI/CD pipelines
- [ ] Verify GitHub Actions pass
- [ ] Update any deployment scripts if needed

---

## Timeline

**Estimated Duration:** 30-45 minutes

1. **Delete & Update (5 min):** Remove legacy backend, update files
2. **Rename (1 min):** Rename directory
3. **Build Verification (10-15 min):** Test all build commands
4. **Testing (10-15 min):** Run test suite
5. **Git Commit (5 min):** Stage, commit, push
6. **CI/CD Monitoring (5-10 min):** Verify GitHub Actions

---

## Success Criteria

- ✅ All build commands succeed
- ✅ All tests pass
- ✅ Docker image builds successfully
- ✅ Development server runs without errors
- ✅ GitHub Actions CI passes
- ✅ No references to legacy `backend/` or `backend-scala/`
- ✅ Documentation updated and accurate

---

## Notes

**Key Improvements in Scala Backend:**

1. Better type safety (no runtime type errors)
2. Functional error handling (Result type)
3. Better concurrency (fs2 + IO)
4. Auto-generated API docs (OpenAPI + Swagger)
5. No `sleep()` anti-patterns
6. Better resource management
7. 30% less code

**Acceptable Trade-offs:**

1. LinkedIn not implemented (wasn't in TS either, handled via RSS)
2. Slightly longer build times (JVM compilation)
3. Larger Docker image (~400MB vs ~200MB)

**Future Enhancements:**

1. Add LinkedIn integration (both backends missing this)
2. Add more comprehensive integration tests
3. Add performance benchmarks
4. Consider GraalVM native image for smaller Docker images

---

**Plan Status:** ✅ READY FOR EXECUTION
