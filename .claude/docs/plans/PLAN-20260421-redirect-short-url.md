# Plan: Redirect API via `/{shortCode}`

**Date**: 2026-04-21  
**Status**: DONE  
**Completed**: 2026-04-21  
**Summary of changes**:  
- Created `src/main/java/com/hhh/url/shorter_url/controller/RedirectController.java`  
- Created `src/test/java/com/hhh/url/shorter_url/controller/RedirectControllerTest.java`  
**Author**: Claude Code  

---

## 1. Requirement Summary
Replace (or supplement) the current `GET /api/v1/urls/redirect?shortCode=xxx` endpoint with a clean root-level redirect: `GET /{shortCode}`. When a browser or HTTP client hits `domain/abc123`, the server returns HTTP 302 with a `Location` header pointing to the original URL — standard URL shortener behaviour.

---

## 2. Scope

### In Scope
- New `RedirectController` at root path `/{shortCode}` returning HTTP 302
- Reuse existing `UrlService.redirect()` (cache + DB lookup, expiry check)
- Proper HTTP semantics: 302 for success, 404 for unknown code, 410 for expired
- Error responses for 404/410 remain JSON via `GlobalExceptionHandler`

### Out of Scope
- Removing the old `/api/v1/urls/redirect` endpoint (keep for backwards compat)
- Analytics / click tracking
- Custom domain routing

---

## 3. Technical Design

### Components to Create
| Component | Type | Location | Responsibility |
|-----------|------|----------|----------------|
| `RedirectController` | Controller | `com.hhh.url.shorter_url.controller` | Handle `GET /{shortCode}`, issue HTTP 302 |

### Components to Modify
| Component | Location | Change Description |
|-----------|----------|--------------------|
| `GlobalExceptionHandler` | `exception/GlobalExceptionHandler.java` | Already handles `ResourceNotFoundException` (404) and `UrlExpiredException` (410) — no change needed |

### API Contract

```
GET /{shortCode}

Success:
  HTTP 302 Found
  Location: https://original-long-url.com/path

Not found:
  HTTP 404 Not Found
  { "status": "ERROR", "message": "Url not found with id: abc123" }

Expired:
  HTTP 410 Gone
  { "status": "ERROR", "message": "URL has expired" }
```

### Key Decisions

- **New controller at `/` root, not inside `UrlController`**  
  **Reason**: `UrlController` is mapped to `/api/v1/urls`. Mixing root-level redirect there would break REST structure.  
  **Alternatives considered**: Adding a `@GetMapping("/{shortCode}")` to `UrlController` — rejected, wrong base path.

- **HTTP 302 (Found) over 301 (Moved Permanently)**  
  **Reason**: 301 is cached by browsers permanently; if a short code is reassigned or deleted, clients would never re-request. 302 always hits the server.  
  **Alternatives considered**: 307 Temporary Redirect — equivalent but 302 is more universally supported for this use case.

- **Reuse `UrlService.redirect()` as-is**  
  **Reason**: It already handles Redis cache, null-sentinel, expiry check, and DB fallback. No duplication needed.

---

## 4. Implementation Steps

- [x] Step 1: Create `RedirectController` with `GET /{shortCode}` returning `ResponseEntity<Void>` with `Location` header and HTTP 302
- [x] Step 2: Verify `GlobalExceptionHandler` correctly propagates 404 / 410 for the new path (should work without changes — confirm with a smoke test)
- [x] Step 3: Write unit test for `RedirectController` covering: success 302, not-found 404, expired 410

---

[//]: # (## 5. Testing Strategy)

[//]: # ()
[//]: # (- **Unit test** `RedirectControllerTest` with `@WebMvcTest`:)

[//]: # (  - `GET /abc123` → mock `urlService.redirect&#40;&#41;` returns URL → assert 302 + `Location` header)

[//]: # (  - `GET /notexist` → mock throws `ResourceNotFoundException` → assert 404)

[//]: # (  - `GET /expired` → mock throws `UrlExpiredException` → assert 410)

[//]: # ()
[//]: # (---)

## 6. Risks & Open Questions

- **Risk**: `/{shortCode}` is very greedy — it matches `/favicon.ico`, `/robots.txt`, etc.  
  **Mitigation**: Spring will route more-specific paths (e.g. `/api/**`, `/actuator/**`) first. Confirm no clashes with existing controllers in smoke test.

- **Open question**: Should the old `/api/v1/urls/redirect` be deprecated with a note? Leaving as-is for now per scope.

---

## 7. Estimated Complexity
[x] Small (< 2h)
