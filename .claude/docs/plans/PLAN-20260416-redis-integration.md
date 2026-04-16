# Plan: Integrate Redis for URL Caching

**Date**: 2026-04-16  
**Status**: DRAFT  
**Author**: Claude Code  

---

## 1. Requirement Summary
Integrate Redis to cache URL mappings (`shortCode` -> `originalUrl`) to improve redirection performance and reduce database load.

## 2. Scope
### In Scope
- Add Spring Data Redis dependency.
- Configure Redis connection and basic cache management.
- Implement caching for `UrlService.redirect(String code)` and `UrlService.getById(long id)`.
- Implement cache eviction when URLs are updated or deleted.

### Out of Scope
- Distributed locking.
- Rate limiting (can be a separate future requirement).
- Storing session data.

## 3. Technical Design

### Components to Create
| Component | Type | Location | Responsibility |
|-----------|------|----------|----------------|
| `RedisConfig` | Configuration | `com.hhh.url.shorter_url.config` | Configures `RedisTemplate` and `CacheManager`. |

### Components to Modify
| Component | Location | Change Description |
|-----------|----------|--------------------|
| `pom.xml` | root | Add `spring-boot-starter-data-redis` dependency. |
| `application.yaml` | `src/main/resources` | Add Redis connection properties. |
| `ShorterUrlApplication` | `com.hhh.url.shorter_url` | Add `@EnableCaching`. |
| `UrlServiceImpl` | `com.hhh.url.shorter_url.service.impl` | Add `@Cacheable`, `@CacheEvict`, and `@CachePut` annotations. |

### Data Model Changes
None. Redis will be used as a cache layer for existing data.

### Key Decisions
- **Decision**: Use Spring Boot's `@Cacheable` abstraction.  
  **Reason**: It provides a clean, declarative way to implement caching without polluting business logic with Redis-specific code.  
  **Alternatives considered**: Manual use of `RedisTemplate`. (Discarded because `@Cacheable` is sufficient and more idiomatic for this use case.)

- **Decision**: Set a Default TTL of 24 hours.  
  **Reason**: Balancing cache freshness with hit rate. Most shortened URLs are accessed frequently within the first few days of creation.

## 4. Implementation Steps

Ordered, atomic tasks:

- [ ] Step 1: Add `spring-boot-starter-data-redis` to `pom.xml`.
- [ ] Step 2: Add Redis configurations to `application.yaml` (host, port, password, TTL).
- [ ] Step 3: Enable caching in `ShorterUrlApplication` with `@EnableCaching`.
- [ ] Step 4: Create `RedisConfig` to customize `CacheManager` (e.g., JSON serialization for values).
- [ ] Step 5: Annotate `UrlService.redirect` with `@Cacheable(value = "url_redirect", key = "#code")`.
- [ ] Step 6: Annotate `UrlService.getById` with `@Cacheable(value = "url_details", key = "#id")`.
- [ ] Step 7: Annotate `UrlService.update` and `UrlService.delete` with `@CacheEvict` for both cache regions to ensure consistency.
- [ ] Step 8: (Optional) Annotate `UrlService.create` with `@CachePut` or simply let it populate the cache on first access.
- [ ] Step 9: Verify integration by monitoring Redis keys while accessing the API.
- [ ] Step 10: Add a unit/integration test to verify cache behavior (using `EmbeddedRedis` or mocking `CacheManager`).

## 5. Testing Strategy
- **Manual Verification**: Run Redis locally (via Docker) and use `redis-cli monitor` to verify cache hits and evictions.
- **Unit Tests**: Mock `UrlRepository` and verify that the repository is NOT called when the cache is hit.
- **Integration Tests**: Use a test profile with an embedded Redis or a real Redis container (Testcontainers) if available.

## 6. Risks & Open Questions
- **Risk**: Cache stale data if eviction logic is missed in some edge cases (e.g., bulk updates). → **Mitigation**: Thoroughly audit all methods that modify the `Url` entity.
- **Risk**: Redis downtime affecting application availability. → **Mitigation**: Ensure Spring Cache fails gracefully (default behavior) by falling back to the database.
- **Open question**: Should we cache the entire `Url` object or just the `originalUrl` string for redirects? (Decided: Cache `originalUrl` for `redirect` and `UrlResponse` for `getById`).

## 7. Estimated Complexity
[ ] Small (< 2h) / [x] Medium (2–8h) / [ ] Large (> 8h)
