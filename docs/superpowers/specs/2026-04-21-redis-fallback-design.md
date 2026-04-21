# Design: Redis Failure Fallback

**Date:** 2026-04-21  
**Status:** Approved  
**Author:** Claude Code

---

## Problem

`UrlServiceImpl` calls `RedisTemplate` directly with no error handling. When the single Redis instance drops, every `GET /api/v1/urls/redirect` request throws an uncaught exception and returns a 500. Redis should be a performance optimization, not a hard dependency.

---

## Goal

When Redis is unavailable, redirect requests fall through transparently to PostgreSQL. Users see no error. Redis failure is logged as a WARN and otherwise ignored.

---

## Architecture

No new classes, no new dependencies. All changes are inside `UrlServiceImpl` and `application.yaml`.

Every Redis call is wrapped in `try/catch (Exception e)`. On failure: log `WARN`, treat the result as a cache miss or skip the write, and continue normally.

`application.yaml` gains explicit connect/command timeouts (500 ms each) so a dead Redis fails fast instead of blocking a thread for tens of seconds.

---

## Affected Code Paths

| Method | Redis operation | On failure |
|---|---|---|
| `redirect()` | `opsForValue().get(key)` | treat as cache miss (null), proceed to DB |
| `redirect()` | `opsForValue().set(key, ...)` | skip silently, log WARN |
| `update()` | `redisTemplate.delete(key)` | skip silently, log WARN |
| `delete()` | `redisTemplate.delete(key)` | skip silently, log WARN |

---

## Implementation Detail

### `redirect()` — read guard

```java
Object cached = null;
try {
    cached = redisTemplate.opsForValue().get(key);
} catch (Exception e) {
    log.warn("Redis read failed, falling back to DB: {}", e.getMessage());
}
```

`null` is already the natural cache-miss value. The NULL sentinel check and `instanceof UrlCacheEntry` check downstream both handle `null` correctly — no other changes needed in that logic.

### `redirect()` — write guard

```java
try {
    redisTemplate.opsForValue().set(key, entry, ttl.toSeconds(), TimeUnit.SECONDS);
} catch (Exception e) {
    log.warn("Redis write failed, cache not populated: {}", e.getMessage());
}
```

Same guard applied to the NULL sentinel write for non-existent short codes.

### `update()` and `delete()` — delete guard

```java
try {
    redisTemplate.delete(shortUrlKey(shortCode));
} catch (Exception e) {
    log.warn("Redis delete failed, stale cache entry may remain: {}", e.getMessage());
}
```

For `update()`, both the old-key and new-key deletes are wrapped in the same try/catch block.

### `application.yaml` — fast-fail timeouts

```yaml
spring:
  data:
    redis:
      connect-timeout: 500ms
      timeout: 500ms
```

Without this, a dead Redis host can stall a thread for up to 60 s (Lettuce default). 500 ms is fast enough to fail quickly without impacting redirect latency significantly.

---

## What Is Not Changed

- No circuit breaker (Resilience4j deferred — appropriate when Redis becomes a replicated production dependency).
- No local Caffeine L1 cache.
- No new classes or interfaces.
- No API contract changes.

---

## Testing

### Unit tests (`UrlServiceImplTest`)

| Scenario | Redis mock behavior | Expected result |
|---|---|---|
| Redis throws on `get` | `opsForValue().get(key)` throws `RedisConnectionFailureException` | DB is called, original URL returned |
| Redis throws on `set` | `opsForValue().set(...)` throws | no exception propagated, original URL still returned |
| Redis throws on `delete` in `update()` | `delete(key)` throws | no exception propagated, update succeeds |
| Redis throws on `delete` in `delete()` | `delete(key)` throws | no exception propagated, delete succeeds |

### Manual smoke test

1. Start app with Redis running — verify normal cache-hit behavior.
2. Stop Redis (`docker-compose stop redis`).
3. Hit `GET /api/v1/urls/redirect?shortCode=xxx` — expect 200 with correct URL, WARN in logs.
4. Restart Redis — verify cache repopulates on next request.
