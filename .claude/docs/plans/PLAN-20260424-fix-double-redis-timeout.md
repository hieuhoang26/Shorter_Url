# Plan: Fix Double Redis Timeout in redirect() Causing ~2s Latency on Redis Down

**Date**: 2026-04-24  
**Status**: DONE  
**Completed**: 2026-04-29  
**Summary of changes**: Modified `UrlServiceImpl.java` — removed `@Cacheable` from `redirect()`, replaced `@Caching` with plain `@CacheEvict(url_details)` on `update()` and `delete()`, removed unused `@Caching` and `@CacheEvict` imports.  
**Author**: Claude Code  

---

## 1. Requirement Summary

When Redis is healthy, `redirect()` responds in 8-12ms. When Redis is down, latency jumps to ~2s before finally hitting the database. The root cause is that `redirect()` makes **two independent Redis connection attempts**, each waiting up to the configured 500ms timeout, before it ever touches the database. Additionally, there is a secondary bug where `@Cacheable` returns a cached String while **bypassing** the `expiredAt` check, potentially serving expired URLs.

---

## 2. Root Cause: Double Timeout

```
Redis DOWN → request for redirect("abc")

① @Cacheable AOP intercepts
   └─ CacheManager.getCache("url_redirect").get("abc")
      └─ RedisTemplate GET "url_redirect::abc"
         └─ connection timeout fires after 500ms  ← FIRST 500ms
         └─ LoggingCacheErrorHandler.handleCacheGetError() → treats as miss
         └─ method body enters

② Inside redirect() body
   └─ redisTemplate.opsForValue().get("short:url:abc")
      └─ connection timeout fires after 500ms  ← SECOND 500ms
      └─ catch(Exception) → falls through to DB

③ DB query                                     ← ~10-50ms

Total wall time: 500 + 500 + ~50 = ~1050ms minimum, observed ~2s
```

### Why Two Attempts?

`redirect()` was annotated with `@Cacheable` (Spring Cache abstraction → Redis via `RedisCacheManager`) **and** manually calls `redisTemplate.opsForValue()` with a completely separate key namespace:

| Layer | Redis key | Value type | TTL |
|-------|-----------|-----------|-----|
| `@Cacheable` AOP | `url_redirect::abc` | raw `String` (originalUrl) | 24h fixed |
| Manual `redisTemplate` | `short:url:abc` | `UrlCacheEntry` (with expiredAt) | dynamic via `computeTtl()` |

These are two separate caches for the same data. When Redis is up and `@Cacheable` already has a hit, the method body never runs — so the manual cache is irrelevant for read performance. But when Redis is down, **both timeouts fire**.

### Secondary Bug: Expiry Bypass

When `@Cacheable` hits (Redis up, second+ request), it returns the raw `String` **directly** — the method body never executes. This means the `expiredAt` check at line 151 is **skipped**. An expired URL will be served from the Spring Cache for up to 24 hours.

---

## 3. Scope

### In Scope
- Remove `@Cacheable` annotation from `redirect()` — rely solely on the existing manual `redisTemplate` implementation, which already handles null-sentinel, dynamic TTL, and expiry checks correctly.
- Remove the now-redundant `@CacheEvict(value = "url_redirect", ...)` clauses from `update()` and `delete()` (nothing writes to that cache anymore).
- Keep all manual `redisTemplate` logic in `redirect()`, `update()`, and `delete()` as-is.
- Keep `@Cacheable(value = "url_details", key = "#id")` on `getById()` — unaffected.

### Out of Scope
- Changing timeout values.
- Circuit-breaker / Resilience4j.
- Changing the manual caching strategy inside `redirect()`.

---

## 4. Technical Design

### Why keep manual Redis instead of `@Cacheable`?

The manual implementation is richer and more correct:
- Stores `UrlCacheEntry` (includes `expiredAt`) → can enforce expiry on cache hit.
- Uses `computeTtl()` → cache entry expires together with the URL, preventing stale entries.
- Writes a null-sentinel (`"NULL"`) on DB miss → prevents cache stampede on non-existent codes.
- `@Cacheable` does none of these: 24h fixed TTL, raw String value, no expiry enforcement.

### Components to Modify

| Component | Location | Change |
|-----------|----------|--------|
| `UrlServiceImpl` | `service/impl/UrlServiceImpl.java` | Remove `@Cacheable` from `redirect()`; remove `@CacheEvict(value="url_redirect")` from `update()` and `delete()` |

### No new classes, no new tests needed.

---

## 5. Implementation Steps

- [x] Step 1: Remove `@Cacheable(value = "url_redirect", key = "#code")` from `redirect()` (line 135).
- [x] Step 2: In `update()` `@Caching`, remove the `@CacheEvict(value = "url_redirect", allEntries = true)` entry (line 219). Keep `@CacheEvict(value = "url_details", key = "#id")`.
- [x] Step 3: In `delete()` `@Caching`, remove the `@CacheEvict(value = "url_redirect", allEntries = true)` entry (line 249). Keep `@CacheEvict(value = "url_details", key = "#id")`.
- [x] Step 4: Remove unused imports: `@Cacheable`, `@Caching`, `@CacheEvict` if no longer referenced.
- [x] Step 5: Verify `redirect()` still works end-to-end: cache hit (Redis up), cache miss (DB fallback), null-sentinel, expired URL (410).

---

## 6. Expected Result After Fix

```
Redis DOWN → request for redirect("abc")

① @Cacheable AOP: GONE — no AOP intercept, no first timeout

② Inside redirect() body
   └─ redisTemplate.opsForValue().get("short:url:abc")
      └─ connection timeout fires after 500ms  ← only one timeout
      └─ catch(Exception) → falls through to DB

③ DB query                                     ← ~10-50ms

Total wall time: 500 + ~50 = ~550ms
```

Also fixes the expiry bypass: every `redirect()` call now goes through the `entry.getExpiredAt()` check at line 151.

---

## 7. Risks & Open Questions

- **Risk**: Cache eviction for `url_redirect` was clearing Spring Cache entries. After removal, nothing writes to that namespace — so no eviction needed. Stale `url_redirect::*` keys may linger in Redis from before the fix. → **Mitigation**: They will expire naturally via the 24h TTL and are now unreachable by the application.
- **Risk**: `update()` and `delete()` used `allEntries = true` eviction for `url_redirect`, which flushed all redirect cache entries on any update. After the fix, the manual `redisTemplate.delete(shortUrlKey(...))` in those methods already handles targeted eviction of `short:url:{code}` — this is actually more precise and better.
- **Open question**: None. The fix is unambiguous.

---

## 8. Estimated Complexity

[x] Small (< 2h) / [ ] Medium (2–8h) / [ ] Large (> 8h)
