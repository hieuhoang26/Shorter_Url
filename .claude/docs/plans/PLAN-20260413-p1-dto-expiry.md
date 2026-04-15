# Plan: P1 — Expose New URL Fields in DTOs + Expiry Enforcement on Redirect

**Date**: 2026-04-13  
**Status**: DONE  
**Author**: Claude Code  
**Completed**: 2026-04-15  
**Summary of changes**:
- Created: `exception/UrlExpiredException.java`
- Modified: `exception/GlobalExceptionHandler.java` — added 410 handler
- Modified: `dto/request/UrlRequest.java` — added `customAlias`, `description`, `tags`
- Modified: `dto/response/UrlResponse.java` — added `customAlias`, `description`, `tags`
- Modified: `mapper/UrlMapper.java` — ignored `shortCode`, `domain`, `expiredAt` on update
- Modified: `service/impl/UrlServiceImpl.java` — alias logic in `create()`/`update()`, expiry check in `redirect()`
- Created: `test/.../service/UrlServiceImplTest.java` — 6 unit tests

---

## 1. Requirement Summary

V3 migration (`V3__add_url_fields.sql`) added three columns to the `urls` table — `custom_alias`, `description`, and `tags` — but none of these fields are exposed through the API layer. `UrlRequest` only accepts `originalUrl`, and `UrlResponse` does not return the new fields. Additionally, the redirect flow (`GET /api/v1/urls/redirect`) never checks `expired_at`, meaning expired links still resolve successfully. This plan completes the V3 integration and enforces link expiry.

---

## 2. Scope

### In Scope
- Add `customAlias`, `description`, `tags` to `UrlRequest` (with validation)
- Add `customAlias`, `description`, `tags` to `UrlResponse`
- Update `UrlMapper` to map new fields in both `toResponse()` and `updateEntityFromRequest()`
- Update `UrlServiceImpl.create()` to persist new fields and handle `customAlias` as an optional short code override
- Update `UrlServiceImpl.redirect()` to throw `ResourceNotFoundException` when `expiredAt < now()`
- Add a new exception `UrlExpiredException` (410 Gone) to distinguish "not found" from "expired"
- Register `UrlExpiredException` in `GlobalExceptionHandler`

### Out of Scope
- Configurable TTL per request (`ttlDays`) — P2
- Click tracking — P2
- Soft delete — P3
- Root-level alias route (`GET /{alias}`) — P3
- Tag-based search/filtering — P3

---

## 3. Technical Design

### Components to Create

| Component | Type | Location | Responsibility |
|-----------|------|----------|----------------|
| `UrlExpiredException` | Exception | `com.hhh.url.shorter_url.exception` | Signals that a URL exists but is past its expiry date |

### Components to Modify

| Component | Location | Change Description |
|-----------|----------|--------------------|
| `UrlRequest` | `dto/request/UrlRequest.java` | Add `customAlias` (optional, `@Pattern`), `description` (optional), `tags` (optional) |
| `UrlResponse` | `dto/response/UrlResponse.java` | Add `customAlias`, `description`, `tags` fields |
| `UrlMapper` | `mapper/UrlMapper.java` | Map new fields in `toResponse()`; add explicit mappings in `updateEntityFromRequest()` for fields that need special handling |
| `UrlServiceImpl` | `service/impl/UrlServiceImpl.java` | `create()` — if `customAlias` provided, set it as `shortCode` and skip Base62 generation; `redirect()` — check `expiredAt` before returning |
| `GlobalExceptionHandler` | `exception/GlobalExceptionHandler.java` | Add `@ExceptionHandler(UrlExpiredException.class)` returning `410 Gone` |

### Data Model Changes

No new migrations needed — V3 already added the columns. No entity changes needed — `Url.java` already has the three fields.

### API Contract

**Create URL — updated request:**
```
POST /api/v1/urls
Request:
{
  "originalUrl": "https://example.com/very-long-path",   // required
  "customAlias": "my-link",                               // optional, [a-zA-Z0-9-], max 100 chars
  "description": "Marketing campaign link",               // optional, free text
  "tags": "marketing,q2,campaign"                         // optional, free text (comma-separated convention)
}

Response 201:
{
  "status": "SUCCESS",
  "message": "Url created successfully",
  "data": {
    "id": 1,
    "shortCode": "my-link",       ← alias used as shortCode when provided
    "originalUrl": "...",
    "domain": "http://localhost:8080",
    "customAlias": "my-link",
    "description": "Marketing campaign link",
    "tags": "marketing,q2,campaign",
    "expiredAt": "...",
    "createdAt": "...",
    ...
  }
}
```

**Redirect — expiry error:**
```
GET /api/v1/urls/redirect?shortCode=my-link

Response 410 (expired):
{
  "status": "ERROR",
  "message": "URL has expired"
}

Response 404 (not found):
{
  "status": "ERROR",
  "message": "Url not found with id: my-link"
}
```

### Key Decisions

- **Decision**: Use `customAlias` directly as the value stored in `shortCode` when provided.  
  **Reason**: The redirect flow only looks up by `shortCode` (`findByShortCode`). Reusing the same field avoids a secondary lookup path and keeps the redirect logic unchanged.  
  **Alternatives considered**: Keep `shortCode` as always Base62 and do `findByShortCode OR findByCustomAlias` in redirect — rejected as extra complexity for no gain at this stage.

- **Decision**: Introduce a distinct `UrlExpiredException` mapped to `410 Gone` rather than reusing `ResourceNotFoundException` (404).  
  **Reason**: HTTP 410 ("Gone") semantically means "the resource existed but is no longer available" — correct for an expired link. 404 implies it was never found. This distinction matters for SEO and client retry logic.  
  **Alternatives considered**: Reuse `ResourceNotFoundException` with a different message — rejected, loses HTTP semantic precision.

- **Decision**: Validate `customAlias` with `@Pattern(regexp = "^[a-zA-Z0-9-]{1,100}$")` on `UrlRequest`.  
  **Reason**: Prevents characters that would be problematic in URLs (spaces, `?`, `#`, `/`). Consistent with common URL slug conventions.  
  **Alternatives considered**: No validation — rejected, could produce broken short URLs.

- **Decision**: `customAlias` uniqueness violation surfaces as `BadRequestException` (400) caught from `DataIntegrityViolationException` in the service layer.  
  **Reason**: The column has a `UNIQUE` constraint. Letting the DB exception bubble as 500 is poor UX. Existing pattern in codebase uses `BadRequestException` for 400s.  
  **Alternatives considered**: Check for existence before insert — rejected, race condition risk; catch at DB exception level is safer.

---

## 4. Implementation Steps

- [x] **Step 1**: Create `UrlExpiredException` in `com.hhh.url.shorter_url.exception`  
  — extends `RuntimeException`, message: `"URL has expired"`

- [x] **Step 2**: Register `UrlExpiredException` in `GlobalExceptionHandler`  
  — return `ResponseEntity` with `HttpStatus.GONE` (410) and `ApiResponse.error(...)`

- [x] **Step 3**: Add `customAlias`, `description`, `tags` to `UrlRequest`  
  — `customAlias`: optional, `@Pattern(regexp = "^[a-zA-Z0-9-]{1,100}$")` with message `"Alias must be alphanumeric with hyphens, max 100 chars"`  
  — `description`, `tags`: optional, no constraint

- [x] **Step 4**: Add `customAlias`, `description`, `tags` to `UrlResponse`

- [x] **Step 5**: Update `UrlMapper`  
  — `toResponse()` will auto-map the new fields (field names match entity)  
  — `updateEntityFromRequest()`: ensure `shortCode` is NOT mapped from request (it's derived); `customAlias`, `description`, `tags` should map

- [x] **Step 6**: Update `UrlServiceImpl.create()`  
  — If `request.getCustomAlias()` is non-null/non-blank → set `entity.setShortCode(request.getCustomAlias())` and `entity.setCustomAlias(request.getCustomAlias())`, skip Base62  
  — Else: existing Base62 flow unchanged  
  — Set `description` and `tags` from request  
  — Wrap `urlRepository.save()` to catch `DataIntegrityViolationException` and rethrow as `BadRequestException("Custom alias already in use")`

- [x] **Step 7**: Update `UrlServiceImpl.redirect()`  
  — After `findByShortCode`, check `entity.getExpiredAt() != null && entity.getExpiredAt().isBefore(LocalDateTime.now())`  
  — Throw `UrlExpiredException` if expired

- [x] **Step 8**: Update `UrlServiceImpl.update()`  
  — After mapper update, if new `customAlias` provided, also update `shortCode` to match  
  — Wrap save in `DataIntegrityViolationException` catch → `BadRequestException`

---

## 5. Testing Strategy

**Unit tests — `UrlServiceImplTest`** (create if not exists):
- `create()` with `customAlias` → `shortCode` equals alias, Base62 not called
- `create()` without `customAlias` → `shortCode` is Base62-generated
- `create()` with duplicate alias → `BadRequestException` thrown
- `redirect()` with valid, non-expired URL → returns `originalUrl`
- `redirect()` with expired URL → `UrlExpiredException` thrown
- `redirect()` with unknown code → `ResourceNotFoundException` thrown

**Integration / controller tests** (optional for P1, mark as P2 if time-constrained):
- `POST /api/v1/urls` with alias → 201, response contains alias as `shortCode`
- `GET /api/v1/urls/redirect?shortCode=expired-link` → 410
- `GET /api/v1/urls/redirect?shortCode=unknown` → 404

**Mocking strategy**: Mock `UrlRepository`, `Base62Service`, `ObjectStorageService` in unit tests using Mockito (`@ExtendWith(MockitoExtension.class)`).

---

## 6. Risks & Open Questions

- **Risk**: `update()` with a new `customAlias` changes `shortCode` — any client holding the old short URL will break.  
  → Mitigation: Document the behavior; consider making `customAlias` immutable after creation in a future iteration.

- **Risk**: `DataIntegrityViolationException` catch in service layer is broad — could mask other constraint violations.  
  → Mitigation: Inspect exception message/cause for `custom_alias` constraint name before rethrowing; otherwise re-throw as-is.

- **Open question**: Should `tags` be stored as a plain comma-separated string (current schema: `TEXT`) or normalized? Current plan assumes plain text per the existing column definition.

---

## 7. Estimated Complexity

[x] Small (< 2h) — All changes are additive, follow established patterns, no new dependencies required.
