# Coding Style Violations — Fix Plan

This document lists violations of the coding style rules in `AGENTS.md` and the minimal fixes needed.

---

## A. Fully Qualified Names in Code

Rule violated: **"Use good imports (no fully qualified names)."**

Types must be imported and referenced without their package prefix. Using `java.net.URI(url)` or `java.util.UUID` inline in code—without a corresponding `import`—violates this rule.

### A1. `backend/common/urls.kt`

- **Lines 17, 19**: `java.net.URI(url)` and `java.net.URI("https://$url")` used as FQ names.
- **Fix**: Add `import java.net.URI` and replace both occurrences with plain `URI(…)`.

### A2. `backend/clients/twitter/TwitterApiModule.kt`

- **Lines 105, 111, 152, 207, 291**: `java.util.UUID` used in function parameter types.
- There is no `import java.util.UUID` in the file.
- **Fix**: Add `import java.util.UUID` and replace all `java.util.UUID` with `UUID`.

### A3. `backend/db/FilesDatabase.kt`

- **Lines 130, 131**: `java.sql.Types.INTEGER` used without `import java.sql.Types`.
- **Fix**: Add `import java.sql.Types` and replace with `Types.INTEGER`.

### A4. `backend/db/migrations.kt`

- **Line 221**: `java.sql.Types.VARCHAR` used without `import java.sql.Types`.
- **Fix**: Add `import java.sql.Types` and replace with `Types.VARCHAR`.

### A5. `backend/db/UsersDatabase.kt`

- **Line 259**: `java.sql.Types.VARCHAR` used without `import java.sql.Types`.
- **Fix**: Add `import java.sql.Types` and replace with `Types.VARCHAR`.

### A6. `frontend/pages/AccountPage.kt`

- **Line 406**: `socialpublish.frontend.models.ConfiguredServices(` used even though `ConfiguredServices` is already imported at line 18.
- **Fix**: Replace with unqualified `ConfiguredServices(`.

---

## B. MVC-Style `models/` Package in Frontend

Rule violated: **"Avoid project-wide MVC-style grouping; instead, group by feature/component. Prefer colocating types with the feature that uses them. Don't introduce silly `models/` or `views/` packages."**

The `frontend/src/jsMain/kotlin/socialpublish/frontend/models/` directory contains three files that create a project-wide `models` package instead of colocating types with the features that own them.

### B1. `frontend/models/Auth.kt` → split

| Type | Sole/primary consumer | Destination |
|---|---|---|
| `LoginRequest` | `pages/LoginPage.kt` | Inline in `pages/LoginPage.kt` |
| `LoginResponse` | `pages/LoginPage.kt` | Inline in `pages/LoginPage.kt` |
| `ConfiguredServices` | `utils/Storage.kt` (stored/retrieved), broadly used | Move to `utils/Storage.kt` |

### B2. `frontend/models/Publish.kt` → split

| Type | Primary consumer | Destination |
|---|---|---|
| `FileUploadResponse` | `pages/PublishFormPage.kt` | Inline in `pages/PublishFormPage.kt` |
| `PublishRequest` | `pages/PublishFormPage.kt` | Inline in `pages/PublishFormPage.kt` |
| `ModulePostResponse` | `pages/PublishFormPage.kt` | Inline in `pages/PublishFormPage.kt` |
| `GenerateAltTextRequest` | `components/ImageUpload.kt` | Inline in `components/ImageUpload.kt` |
| `GenerateAltTextResponse` | `components/ImageUpload.kt` | Inline in `components/ImageUpload.kt` |

### B3. `frontend/models/data.kt` → merge

| Symbol | Sole consumer | Destination |
|---|---|---|
| `LANGUAGE_OPTIONS` | `pages/PublishFormPage.kt` | Inline in `pages/PublishFormPage.kt` |

After all types are moved, the three source files in `models/` are deleted, and all `import socialpublish.frontend.models.*` statements are updated accordingly.

---

## Checklist

- [x] Save this plan to `./plans/style-violations.md`
- [x] A1 – Fix FQ names in `backend/common/urls.kt`
- [x] A2 – Fix FQ names in `backend/clients/twitter/TwitterApiModule.kt`
- [x] A3 – Fix FQ names in `backend/db/FilesDatabase.kt`
- [x] A4 – Fix FQ names in `backend/db/migrations.kt`
- [x] A5 – Fix FQ names in `backend/db/UsersDatabase.kt`
- [x] A6 – Fix FQ name in `frontend/pages/AccountPage.kt`
- [x] B1 – Move `Auth.kt` types out of `models/`
- [x] B2 – Move `Publish.kt` types out of `models/`
- [x] B3 – Move `data.kt` content out of `models/`
- [x] Delete `frontend/models/` directory
- [x] Update all import references to removed `models/` types
- [x] Run `make format` and `make test` to verify correctness
