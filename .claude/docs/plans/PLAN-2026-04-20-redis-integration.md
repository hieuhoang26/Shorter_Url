# Plan: Redis Caching Integration

**Date**: 2026-04-20
**Status**: DONE
**Completed**: 2026-04-20
**Summary of changes**:
- Created: `docker-compose.yml`, `RedisConfig`, `CacheKeyConstant`, `UrlCacheEntry`
- Modified: `pom.xml`, `application.yaml`, `UrlServiceImpl`
- Tests written: `UrlServiceImplTest`, `RedisIntegrationTest` (skipped running)
**Author**: Claude Code

---

## 1. Requirement Summary

Integrate Redis as a read-through cache layer in front of PostgreSQL for the URL shortener service. The redirect path (`GET /api/v1/urls/redirect?shortCode=xxx`) is the critical hot path and is read-heavy by nature. Redis will be placed between the application and the database so that repeated redirect lookups are served from memory instead of hitting PostgreSQL. Cache entries will be invalidated on update and delete. NULL values will be cached with a short TTL to defend against cache penetration from invalid shortCodes.

---

## 2. Scope

### In Scope
- Add `spring-boot-starter-data-redis` (Lettuce) dependency to `pom.xml`
- Add Redis service to a new `docker-compose.yml` (project currently has none)
- Create `RedisConfig` bean with JSON serialization via `GenericJackson2JsonRedisSerializer`
- Create `CacheKeyConstant` utility class for key pattern constants
- Create `UrlCacheEntry` DTO (serializable value stored in Redis)
- Modify `UrlServiceImpl.redirect()` to apply cache-aside pattern: Redis first → DB fallback → populate cache
- TTL logic: 24 h for links with no expiry; `expiredAt - now()` (minimum 1 s, skipped if already expired) for links with a future `expiredAt`
- NULL-value caching for non-existent shortCodes (5-minute TTL) to prevent cache penetration
- Cache invalidation in `UrlServiceImpl.update()` and `UrlServiceImpl.delete()`
- Add `spring.data.redis.*` configuration block to `application.yaml`
- Unit tests for the new caching logic in `UrlServiceImpl`
- Integration test skeleton using an embedded/testcontainers Redis

### Out of Scope
- Cache breakdown protection (Redisson distributed locks)
- Hot key local caching (Caffeine L1 cache)
- Circuit breaker / fallback when Redis is unavailable (Resilience4j)
- Prometheus / Grafana monitoring
- Redis Cluster or Sentinel configuration
- Caching responses for `getById` or `getAll` endpoints

---

## 3. Technical Design

### Components to Create

| Component | Type | Location | Responsibility |
|-----------|------|----------|----------------|
| `RedisConfig` | `@Configuration` | `com.hhh.url.shorter_url.config` | Declares `RedisTemplate<String, Object>` bean with String key serializer and Jackson JSON value serializer |
| `CacheKeyConstant` | Constants class | `com.hhh.url.shorter_url.util` | Holds key prefix `"short:url:"` and sentinel `"NULL"` string |
| `UrlCacheEntry` | DTO (Serializable) | `com.hhh.url.shorter_url.dto.cache` | Value type stored in Redis: `originalUrl` + `expiredAt` fields |
| `docker-compose.yml` | Infrastructure | project root | Runs Redis 7 with AOF persistence and named volume |

### Components to Modify

| Component | Location | Change Description |
|-----------|----------|--------------------|
| `pom.xml` | project root | Add `spring-boot-starter-data-redis` dependency |
| `application.yaml` | `src/main/resources` | Add `spring.data.redis` block (host, port, timeout, lettuce pool settings) |
| `UrlServiceImpl` | `com.hhh.url.shorter_url.service.impl` | Inject `RedisTemplate`; modify `redirect()`, `update()`, `delete()` |

### Data Model Changes

No database schema changes. Redis stores only transient cache entries; no Liquibase migration needed.

**Redis value structure** (JSON, stored as `UrlCacheEntry`):
```json
{
  "originalUrl": "https://example.com/some/path",
  "expiredAt": "2026-12-01T00:00:00"
}
```
`expiredAt` may be `null` for links with no expiry.

**Sentinel value** (stored as raw String for NULL caching):
```
"NULL"
```
The sentinel is a plain `String`, not a `UrlCacheEntry`, so deserialization must check for it before casting.

### API Contract (if applicable)

No API contract changes. Redis is an internal implementation detail; all existing endpoints keep the same request/response shapes.

### Key Decisions

- **Decision**: Use `RedisTemplate<String, Object>` with `GenericJackson2JsonRedisSerializer` rather than Spring Cache abstraction (`@Cacheable`).
  **Reason**: The TTL per entry must be computed dynamically at runtime based on `expiredAt`. Spring's `@Cacheable` with a fixed TTL `CacheManager` cannot express per-entry TTL without a custom `RedisCacheWriter`. Manual template usage keeps the logic explicit and testable.
  **Alternatives considered**: `@Cacheable` + custom `RedisCacheManager` — rejected as more ceremony for the same result; Spring Cache does not natively support per-entry TTL without low-level overrides.

- **Decision**: Cache the `UrlCacheEntry` DTO, not the full `Url` entity or `UrlResponse`.
  **Reason**: Only `originalUrl` and `expiredAt` are needed in the redirect path. Caching the minimal projection reduces serialized payload size and avoids coupling Redis values to the full entity shape (including audit fields).
  **Alternatives considered**: Caching `UrlResponse` — rejected because it includes audit metadata irrelevant to redirect.

- **Decision**: Inject `RedisTemplate` directly into `UrlServiceImpl` rather than extracting a `UrlCacheService`.
  **Reason**: The cache operations are tightly coupled to the service's business logic (TTL depends on `expiredAt`, invalidation must be transactionally ordered). Keeping them co-located avoids an extra indirection layer. The service is already the right unit to own this.
  **Alternatives considered**: Separate `UrlCacheService` — acceptable if the class grows large, but premature at this scope.

- **Decision**: Check cache before expiry validation; rely on Redis TTL to evict stale expired-link entries rather than re-validating `expiredAt` from the cache value.
  **Reason**: For links with a finite `expiredAt`, the Redis TTL is set to `expiredAt - now()`, so the entry disappears from Redis at the same moment the link expires. A cache hit therefore guarantees the link was not yet expired at cache population time, and the remaining TTL ensures it will not be served after expiry. The `expiredAt` field is still stored in the cached value and validated on cache hit to handle any clock skew edge cases.
  **Alternatives considered**: Re-validate expiry on every cache hit — adds a check but is included as a safety net (see implementation steps).

- **Decision**: Store `"NULL"` sentinel as a plain string for non-existent shortCodes; distinguish from `UrlCacheEntry` by an `instanceof` check.
  **Reason**: `GenericJackson2JsonRedisSerializer` embeds the class name in the JSON, so a deserialized `String` and a deserialized `UrlCacheEntry` are distinguishable at the Java type level without an additional flag field.
  **Alternatives considered**: Wrap sentinel in a `UrlCacheEntry` with a `notFound=true` flag — overly complex; a raw string is simpler.

---

## 4. Implementation Steps

- [x] **Step 1**: Add `spring-boot-starter-data-redis` to `pom.xml` (no version needed; managed by Spring Boot BOM)

- [x] **Step 2**: Create `docker-compose.yml` at project root with a `redis` service (image `redis:7`, port `6379:6379`, `--appendonly yes` command, named volume `redis_data`)

- [x] **Step 3**: Add Redis configuration block to `application.yaml`:
  ```yaml
  spring:
    data:
      redis:
        host: ${REDIS_HOST:localhost}
        port: ${REDIS_PORT:6379}
        timeout: 2000ms
        lettuce:
          pool:
            max-active: 8
            max-idle: 8
            min-idle: 2
  ```
  Note: Spring Boot 3.x uses `spring.data.redis` (not `spring.redis`).

- [x] **Step 4**: Create `CacheKeyConstant` in `com.hhh.url.shorter_url.util`:
  ```java
  public final class CacheKeyConstant {
      public static final String SHORT_URL_PREFIX = "short:url:";
      public static final String NULL_SENTINEL = "NULL";
      public static final long NULL_TTL_MINUTES = 5;
      public static final long DEFAULT_TTL_HOURS = 24;

      private CacheKeyConstant() {}

      public static String shortUrlKey(String shortCode) {
          return SHORT_URL_PREFIX + shortCode;
      }
  }
  ```

- [x] **Step 5**: Create `UrlCacheEntry` DTO in `com.hhh.url.shorter_url.dto.cache`:
  ```java
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public class UrlCacheEntry implements Serializable {
      private String originalUrl;
      private LocalDateTime expiredAt;   // null means no expiry
  }
  ```
  Must be `Serializable` and have a no-arg constructor for Jackson deserialization.

- [x] **Step 6**: Create `RedisConfig` in `com.hhh.url.shorter_url.config`:
  ```java
  @Configuration
  public class RedisConfig {

      @Bean
      public RedisTemplate<String, Object> redisTemplate(
              RedisConnectionFactory connectionFactory) {
          RedisTemplate<String, Object> template = new RedisTemplate<>();
          template.setConnectionFactory(connectionFactory);
          template.setKeySerializer(new StringRedisSerializer());
          template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
          template.setHashKeySerializer(new StringRedisSerializer());
          template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
          template.afterPropertiesSet();
          return template;
      }
  }
  ```

- [x] **Step 7**: Modify `UrlServiceImpl` — inject `RedisTemplate`:
  Add field:
  ```java
  private final RedisTemplate<String, Object> redisTemplate;
  ```
  `@RequiredArgsConstructor` picks it up automatically.

- [x] **Step 8**: Modify `UrlServiceImpl.redirect()` — implement cache-aside with NULL caching:

  **Current:**
  ```java
  public String redirect(String code) {
      Url entity = urlRepository.findByShortCode(code)
              .orElseThrow(() -> new ResourceNotFoundException("Url not found with id: " + code));
      if (entity.getExpiredAt() != null && entity.getExpiredAt().isBefore(LocalDateTime.now())) {
          throw new UrlExpiredException("URL has expired");
      }
      return entity.getOriginalUrl();
  }
  ```

  **New logic (pseudocode, to be translated to Java):**
  ```
  key = CacheKeyConstant.shortUrlKey(code)

  cached = redisTemplate.opsForValue().get(key)

  if cached == NULL_SENTINEL:
      throw ResourceNotFoundException

  if cached instanceof UrlCacheEntry entry:
      // Safety net: validate expiredAt from cached value
      if entry.expiredAt != null && entry.expiredAt.isBefore(now):
          throw UrlExpiredException
      return entry.originalUrl

  // Cache miss: hit the DB
  optionalUrl = urlRepository.findByShortCode(code)

  if optionalUrl is empty:
      redisTemplate.opsForValue().set(key, NULL_SENTINEL, 5, MINUTES)
      throw ResourceNotFoundException

  entity = optionalUrl.get()

  if entity.expiredAt != null && entity.expiredAt.isBefore(now):
      throw UrlExpiredException      // do NOT cache expired links

  entry = UrlCacheEntry(entity.originalUrl, entity.expiredAt)
  ttl = computeTtl(entity.expiredAt)
  redisTemplate.opsForValue().set(key, entry, ttl.seconds, SECONDS)

  return entity.originalUrl
  ```

  **TTL computation helper (private method):**
  ```
  private Duration computeTtl(LocalDateTime expiredAt) {
      if (expiredAt == null) {
          return Duration.ofHours(DEFAULT_TTL_HOURS);
      }
      Duration remaining = Duration.between(now(), expiredAt);
      if (remaining.isNegative() || remaining.isZero()) {
          return Duration.ofSeconds(1);   // defensive: should not reach here normally
      }
      // Cap at 24 h to avoid extremely long TTLs for links expiring far in the future
      return remaining.compareTo(Duration.ofHours(DEFAULT_TTL_HOURS)) < 0
             ? remaining
             : Duration.ofHours(DEFAULT_TTL_HOURS);
  }
  ```

- [x] **Step 9**: Modify `UrlServiceImpl.update()` — evict cache after DB update:
  Add at the end of the method, after `urlRepository.save(entity)` succeeds:
  ```java
  redisTemplate.delete(CacheKeyConstant.shortUrlKey(entity.getShortCode()));
  ```
  Note: `entity.getShortCode()` reflects the updated value (set from `request.getCustomAlias()` if provided). If the shortCode was changed, the old key is also stale — fetch the old shortCode **before** the mapper update and delete both old and new keys.

  **Refined update invalidation:**
  ```java
  // Capture old shortCode before mutation
  String oldShortCode = entity.getShortCode();
  urlMapper.updateEntityFromRequest(request, entity);
  if (request.getCustomAlias() != null && !request.getCustomAlias().isBlank()) {
      entity.setShortCode(request.getCustomAlias());
  }
  Url savedEntity = urlRepository.save(entity);
  // Evict old key; if shortCode changed, also evict new key to be safe
  redisTemplate.delete(CacheKeyConstant.shortUrlKey(oldShortCode));
  if (!oldShortCode.equals(savedEntity.getShortCode())) {
      redisTemplate.delete(CacheKeyConstant.shortUrlKey(savedEntity.getShortCode()));
  }
  return urlMapper.toResponse(savedEntity);
  ```

- [x] **Step 10**: Modify `UrlServiceImpl.delete()` — evict cache after DB delete:
  Capture `shortCode` before deletion:
  ```java
  String shortCode = entity.getShortCode();
  urlRepository.delete(entity);
  redisTemplate.delete(CacheKeyConstant.shortUrlKey(shortCode));
  ```

- [x] **Step 11**: Write unit tests for `UrlServiceImpl` cache logic (see Section 5).

- [x] **Step 12**: Write an integration test for the redirect endpoint with a Testcontainers Redis instance (see Section 5).

- [x] **Step 13**: Manual smoke test: start Redis via `docker-compose up -d`, run the application, create a URL, hit redirect twice, confirm second call returns without a DB query (verify via `show-sql: true` log output).

---

## 5. Testing Strategy

### Unit Tests — `UrlServiceImplTest`

Create `src/test/java/com/hhh/url/shorter_url/service/impl/UrlServiceImplTest.java` using Mockito.

Mock dependencies: `UrlRepository`, `Base62Service`, `UrlMapper`, `ObjectStorageService`, `RedisTemplate`, `ValueOperations` (returned by `redisTemplate.opsForValue()`).

**Cases to cover for `redirect()`:**

| Test case | Redis state | DB state | Expected result |
|-----------|-------------|----------|-----------------|
| Cache hit — valid link | `UrlCacheEntry` present | not called | returns `originalUrl` |
| Cache hit — NULL sentinel | `"NULL"` present | not called | throws `ResourceNotFoundException` |
| Cache hit — expired entry (clock skew edge case) | `UrlCacheEntry` with past `expiredAt` | not called | throws `UrlExpiredException` |
| Cache miss — valid link | empty | record found, not expired | returns `originalUrl`; verify `redisTemplate.opsForValue().set(key, entry, ttl, SECONDS)` called |
| Cache miss — valid link with future `expiredAt` | empty | record found | verify TTL is `min(expiredAt - now, 24h)` in seconds |
| Cache miss — link not found | empty | empty | throws `ResourceNotFoundException`; verify NULL sentinel cached with 5 min TTL |
| Cache miss — expired link in DB | empty | record found, past `expiredAt` | throws `UrlExpiredException`; verify nothing cached |

**Cases to cover for `update()`:**

| Test case | Expected cache behavior |
|-----------|------------------------|
| Update with same shortCode | `delete(key)` called once for old shortCode |
| Update changing customAlias (shortCode changes) | `delete(oldKey)` and `delete(newKey)` both called |

**Cases to cover for `delete()`:**

| Test case | Expected cache behavior |
|-----------|------------------------|
| Delete existing URL | `delete(key)` called with correct shortCode |

### Integration Tests — `RedisIntegrationTest`

Use `@SpringBootTest` + Testcontainers (`testcontainers-redis` or generic container with `redis:7` image).

Add Testcontainers dependency to `pom.xml` test scope:
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```
(version managed by Spring Boot BOM via `spring-boot-testcontainers`).

**Integration test flow:**
1. Start a real Redis container via `@Container`.
2. Override `spring.data.redis.host` and `spring.data.redis.port` with the container's mapped port.
3. Insert a test `Url` row via `UrlRepository`.
4. Call `urlService.redirect(shortCode)` once — assert DB was hit (check `UrlRepository` mock or query count).
5. Call `urlService.redirect(shortCode)` again — assert the result is the same and no DB query was issued (verify Redis key exists via `RedisTemplate`).
6. Call `urlService.update(id, request)` — assert Redis key is deleted.
7. Call `urlService.redirect(shortCode)` once more — assert DB was hit again (cache miss after invalidation).

### Mocking Strategy
- Unit tests: pure Mockito, no Spring context. Inject mocks manually via constructor or `@InjectMocks`.
- Integration tests: real Spring context with Testcontainers Redis; mock AWS S3 with `@MockBean ObjectStorageService` to avoid real S3 calls during test startup (`initTemplate` `@EventListener` uses it).

---

## 6. Risks & Open Questions

- **Risk**: `GenericJackson2JsonRedisSerializer` embeds the fully-qualified class name in JSON. Renaming or moving `UrlCacheEntry` will break deserialization of existing cache entries.
  **Mitigation**: Do not rename or relocate `UrlCacheEntry` without a Redis flush (`FLUSHDB`) or a migration strategy. Document this constraint in the class Javadoc.

- **Risk**: `update()` currently reads the entity, then mutates it via `urlMapper.updateEntityFromRequest()`. The old `shortCode` must be captured before the mapper overwrites it; otherwise the wrong key is evicted.
  **Mitigation**: Implementation step 9 explicitly captures `oldShortCode` before mutation. Unit test covers the shortCode-change scenario.

- **Risk**: Clock drift between app nodes if deployed as multiple instances. A cached entry's `expiredAt` safety check uses local clock.
  **Mitigation**: The Redis TTL is the primary enforcement mechanism. The in-memory `expiredAt` check is a belt-and-suspenders guard. Acceptable for the current single-instance deployment.

- **Risk**: Redis unavailability causes all redirect requests to fail if the `RedisTemplate` call throws an uncaught exception.
  **Mitigation**: Out of scope (Resilience4j circuit breaker deferred). For now, log the exception and let the request fail with 500. Document that Redis is a hard dependency in this implementation. A future follow-up can add a try-catch fallback to DB.

- **Open question**: Should the Lettuce connection pool (`commons-pool2`) be added explicitly, or rely on the default Lettuce connection sharing model?
  **Assumption**: For simplicity, no connection pooling dependency is added. The Lettuce driver uses multiplexed connections by default, which is adequate for the current load. If `spring.data.redis.lettuce.pool.*` properties are set in `application.yaml`, `commons-pool2` must be on the classpath — add it only if pooling is explicitly enabled.

- **Open question**: Should `redirect()` remain non-transactional (current) even with Redis calls mixed in?
  **Assumption**: Yes. Redis operations are not transactional with the DB. The method is a read path and does not require `@Transactional`. Cache population after a DB read is idempotent.

---

## 7. Estimated Complexity

[ ] Small (< 2h) / [x] Medium (2–8h) / [ ] Large (> 8h)

Estimated at 4–5 hours: ~1h for dependencies and config, ~1h for new classes (`RedisConfig`, `CacheKeyConstant`, `UrlCacheEntry`), ~1h for `UrlServiceImpl` modifications, ~1.5h for unit and integration tests.
