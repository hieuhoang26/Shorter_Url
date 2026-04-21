# Redis Failure Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Redis a transparent performance optimization — when Redis drops, all requests fall through to PostgreSQL with no error surfaced to callers.

**Architecture:** Two layers of protection: (1) a `CacheErrorHandler` registered on `RedisCacheManager` swallows Spring Cache annotation failures (`@Cacheable`, `@CacheEvict`); (2) `try/catch` blocks around every manual `redisTemplate` call in `UrlServiceImpl` log a WARN and continue. Fast-fail timeouts (500 ms) in `application.yaml` prevent a dead Redis from blocking threads.

**Tech Stack:** Spring Boot 3.5, Spring Data Redis (Lettuce), Mockito (unit tests)

---

## File Map

| Action | File | What changes |
|---|---|---|
| Create | `src/main/java/com/hhh/url/shorter_url/config/LoggingCacheErrorHandler.java` | New class: swallows Spring Cache annotation errors and logs WARN |
| Modify | `src/main/java/com/hhh/url/shorter_url/config/RedisConfig.java` | Implement `CachingConfigurer`, register `LoggingCacheErrorHandler` |
| Modify | `src/main/java/com/hhh/url/shorter_url/service/impl/UrlServiceImpl.java` | Wrap 4 manual `redisTemplate` call sites in `try/catch` |
| Modify | `src/main/resources/application.yaml` | Add `connect-timeout: 500ms` and `timeout: 500ms` under `spring.data.redis` |
| Create | `src/test/java/com/hhh/url/shorter_url/service/impl/UrlServiceImplTest.java` | Unit tests: Redis throws → verify fallback behavior |

---

### Task 1: Add `LoggingCacheErrorHandler`

**Files:**
- Create: `src/main/java/com/hhh/url/shorter_url/config/LoggingCacheErrorHandler.java`

This class handles failures from `@Cacheable`, `@CacheEvict`, and `@CachePut` annotations. Without it, a Redis outage causes those annotations to throw before the method body even runs.

- [ ] **Step 1: Create the file**

```java
package com.hhh.url.shorter_url.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

@Slf4j
public class LoggingCacheErrorHandler implements CacheErrorHandler {

    @Override
    public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
        log.warn("Cache GET failed [cache={}, key={}]: {}", cache.getName(), key, e.getMessage());
    }

    @Override
    public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
        log.warn("Cache PUT failed [cache={}, key={}]: {}", cache.getName(), key, e.getMessage());
    }

    @Override
    public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
        log.warn("Cache EVICT failed [cache={}, key={}]: {}", cache.getName(), key, e.getMessage());
    }

    @Override
    public void handleCacheClearError(RuntimeException e, Cache cache) {
        log.warn("Cache CLEAR failed [cache={}]: {}", cache.getName(), e.getMessage());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/hhh/url/shorter_url/config/LoggingCacheErrorHandler.java
git commit -m "feat(cache): add LoggingCacheErrorHandler for Spring Cache annotation fallback"
```

---

### Task 2: Register `LoggingCacheErrorHandler` in `RedisConfig`

**Files:**
- Modify: `src/main/java/com/hhh/url/shorter_url/config/RedisConfig.java`

`CachingConfigurer` is the Spring 6 interface for customizing the cache infrastructure. Implementing it on `RedisConfig` and overriding `errorHandler()` wires our handler to every `@Cacheable`/`@CacheEvict` call in the application.

- [ ] **Step 1: Update `RedisConfig` to implement `CachingConfigurer` and register the handler**

Replace the entire file with:

```java
package com.hhh.url.shorter_url.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig implements CachingConfigurer {

    @Value("${cache.ttl-hours:24}")
    private long ttlHours;

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(mapper);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(ttlHours))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
```

- [ ] **Step 2: Verify the app still compiles**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS with no errors.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hhh/url/shorter_url/config/RedisConfig.java
git commit -m "feat(cache): register LoggingCacheErrorHandler on RedisCacheManager"
```

---

### Task 3: Add fast-fail timeouts to `application.yaml`

**Files:**
- Modify: `src/main/resources/application.yaml`

Without explicit timeouts, Lettuce waits up to 60 s for a connection to a dead Redis host. 500 ms is aggressive enough to fail fast while still accommodating a briefly loaded Redis.

- [ ] **Step 1: Add `connect-timeout` and `timeout` under `spring.data.redis`**

The current block is:
```yaml
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
```

Replace it with:
```yaml
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      connect-timeout: 500ms
      timeout: 500ms
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yaml
git commit -m "config(redis): set 500ms connect/command timeout for fast failure"
```

---

### Task 4: Guard manual Redis calls in `redirect()`

**Files:**
- Modify: `src/main/java/com/hhh/url/shorter_url/service/impl/UrlServiceImpl.java`

There are three `redisTemplate` call sites inside `redirect()`: one `get`, one `set` for the NULL sentinel, and one `set` for a valid cache entry. Each gets its own `try/catch`.

- [ ] **Step 1: Replace the `redirect()` method body**

Current method (lines 134–170):
```java
@Override
@Cacheable(value = "url_redirect", key = "#code")
public String redirect(String code) {
    String key = shortUrlKey(code);
    Object cached = redisTemplate.opsForValue().get(key);

    if (NULL_SENTINEL.equals(cached)) {
        throw new ResourceNotFoundException("Url not found with id: " + code);
    }

    if (cached instanceof UrlCacheEntry entry) {
        if (entry.getExpiredAt() != null && entry.getExpiredAt().isBefore(LocalDateTime.now())) {
            throw new UrlExpiredException("URL has expired");
        }
        return entry.getOriginalUrl();
    }

    Optional<Url> optional = urlRepository.findByShortCode(code);
    if (optional.isEmpty()) {
        redisTemplate.opsForValue().set(key, NULL_SENTINEL, NULL_TTL_MINUTES, TimeUnit.MINUTES);
        throw new ResourceNotFoundException("Url not found with id: " + code);
    }

    Url entity = optional.get();
    if (entity.getExpiredAt() != null && entity.getExpiredAt().isBefore(LocalDateTime.now())) {
        throw new UrlExpiredException("URL has expired");
    }

    UrlCacheEntry entry = UrlCacheEntry.builder()
            .originalUrl(entity.getOriginalUrl())
            .expiredAt(entity.getExpiredAt())
            .build();
    Duration ttl = computeTtl(entity.getExpiredAt());
    redisTemplate.opsForValue().set(key, entry, ttl.toSeconds(), TimeUnit.SECONDS);

    return entity.getOriginalUrl();
}
```

Replace with:
```java
@Override
@Cacheable(value = "url_redirect", key = "#code")
public String redirect(String code) {
    String key = shortUrlKey(code);

    Object cached = null;
    try {
        cached = redisTemplate.opsForValue().get(key);
    } catch (Exception e) {
        log.warn("Redis read failed, falling back to DB: {}", e.getMessage());
    }

    if (NULL_SENTINEL.equals(cached)) {
        throw new ResourceNotFoundException("Url not found with id: " + code);
    }

    if (cached instanceof UrlCacheEntry entry) {
        if (entry.getExpiredAt() != null && entry.getExpiredAt().isBefore(LocalDateTime.now())) {
            throw new UrlExpiredException("URL has expired");
        }
        return entry.getOriginalUrl();
    }

    Optional<Url> optional = urlRepository.findByShortCode(code);
    if (optional.isEmpty()) {
        try {
            redisTemplate.opsForValue().set(key, NULL_SENTINEL, NULL_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis write failed, null sentinel not cached: {}", e.getMessage());
        }
        throw new ResourceNotFoundException("Url not found with id: " + code);
    }

    Url entity = optional.get();
    if (entity.getExpiredAt() != null && entity.getExpiredAt().isBefore(LocalDateTime.now())) {
        throw new UrlExpiredException("URL has expired");
    }

    UrlCacheEntry entry = UrlCacheEntry.builder()
            .originalUrl(entity.getOriginalUrl())
            .expiredAt(entity.getExpiredAt())
            .build();
    Duration ttl = computeTtl(entity.getExpiredAt());
    try {
        redisTemplate.opsForValue().set(key, entry, ttl.toSeconds(), TimeUnit.SECONDS);
    } catch (Exception e) {
        log.warn("Redis write failed, cache not populated: {}", e.getMessage());
    }

    return entity.getOriginalUrl();
}
```

- [ ] **Step 2: Compile to verify no errors**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hhh/url/shorter_url/service/impl/UrlServiceImpl.java
git commit -m "feat(cache): guard redirect() Redis calls with try/catch fallback"
```

---

### Task 5: Guard manual Redis calls in `update()` and `delete()`

**Files:**
- Modify: `src/main/java/com/hhh/url/shorter_url/service/impl/UrlServiceImpl.java`

- [ ] **Step 1: Replace the Redis delete block inside `update()`**

Current block inside the `try { ... } catch (DataIntegrityViolationException ex)` in `update()`:
```java
Url savedEntity = urlRepository.save(entity);
redisTemplate.delete(shortUrlKey(oldShortCode));
if (!oldShortCode.equals(savedEntity.getShortCode())) {
    redisTemplate.delete(shortUrlKey(savedEntity.getShortCode()));
}
return urlMapper.toResponse(savedEntity);
```

Replace with:
```java
Url savedEntity = urlRepository.save(entity);
try {
    redisTemplate.delete(shortUrlKey(oldShortCode));
    if (!oldShortCode.equals(savedEntity.getShortCode())) {
        redisTemplate.delete(shortUrlKey(savedEntity.getShortCode()));
    }
} catch (Exception e) {
    log.warn("Redis delete failed during update, stale entry may remain: {}", e.getMessage());
}
return urlMapper.toResponse(savedEntity);
```

- [ ] **Step 2: Replace the Redis delete call in `delete()`**

Current lines at the end of `delete()`:
```java
urlRepository.delete(entity);
redisTemplate.delete(shortUrlKey(shortCode));
```

Replace with:
```java
urlRepository.delete(entity);
try {
    redisTemplate.delete(shortUrlKey(shortCode));
} catch (Exception e) {
    log.warn("Redis delete failed during delete, stale entry may remain: {}", e.getMessage());
}
```

- [ ] **Step 3: Compile to verify no errors**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/hhh/url/shorter_url/service/impl/UrlServiceImpl.java
git commit -m "feat(cache): guard update() and delete() Redis calls with try/catch fallback"
```

---

### Task 6: Write unit tests for fallback behavior

**Files:**
- Create: `src/test/java/com/hhh/url/shorter_url/service/impl/UrlServiceImplTest.java`

These tests verify that a Redis `RuntimeException` never propagates to the caller. They use pure Mockito — no Spring context needed.

- [ ] **Step 1: Create the test file**

```java
package com.hhh.url.shorter_url.service.impl;

import com.hhh.url.shorter_url.dto.cache.UrlCacheEntry;
import com.hhh.url.shorter_url.dto.request.UrlRequest;
import com.hhh.url.shorter_url.dto.response.UrlResponse;
import com.hhh.url.shorter_url.exception.ResourceNotFoundException;
import com.hhh.url.shorter_url.mapper.UrlMapper;
import com.hhh.url.shorter_url.model.Url;
import com.hhh.url.shorter_url.repository.UrlRepository;
import com.hhh.url.shorter_url.service.Base62Service;
import com.hhh.url.shorter_url.service.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.hhh.url.shorter_url.util.CacheKeyConstant.shortUrlKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceImplTest {

    @Mock UrlRepository urlRepository;
    @Mock Base62Service base62Service;
    @Mock UrlMapper urlMapper;
    @Mock ObjectStorageService objectStorageService;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;

    @InjectMocks UrlServiceImpl service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // --- redirect() ---

    @Test
    void redirect_redisGetThrows_fallsBackToDbAndReturnsUrl() {
        when(valueOps.get(shortUrlKey("abc"))).thenThrow(new RedisConnectionFailureException("connection refused"));

        Url entity = urlEntity("abc", "https://example.com", null);
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(entity));
        doNothing().when(valueOps).set(anyString(), any(), anyLong(), any(TimeUnit.class));

        String result = service.redirect("abc");

        assertThat(result).isEqualTo("https://example.com");
        verify(urlRepository).findByShortCode("abc");
    }

    @Test
    void redirect_redisWriteThrows_returnsUrlAnyway() {
        when(valueOps.get(shortUrlKey("abc"))).thenReturn(null);

        Url entity = urlEntity("abc", "https://example.com", null);
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(entity));
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOps).set(anyString(), any(), anyLong(), any(TimeUnit.class));

        String result = service.redirect("abc");

        assertThat(result).isEqualTo("https://example.com");
    }

    @Test
    void redirect_notFoundAndNullSentinelWriteThrows_stillThrows404() {
        when(valueOps.get(shortUrlKey("missing"))).thenReturn(null);
        when(urlRepository.findByShortCode("missing")).thenReturn(Optional.empty());
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOps).set(anyString(), any(), anyLong(), any(TimeUnit.class));

        assertThatThrownBy(() -> service.redirect("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- update() ---

    @Test
    void update_redisDeleteThrows_updateStillSucceeds() {
        Url entity = urlEntity("abc", "https://example.com", null);
        when(urlRepository.findById(1L)).thenReturn(Optional.of(entity));
        doNothing().when(urlMapper).updateEntityFromRequest(any(), any());
        when(urlRepository.save(any())).thenReturn(entity);
        when(urlMapper.toResponse(any())).thenReturn(new UrlResponse());
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(redisTemplate).delete(anyString());

        UrlRequest request = new UrlRequest();

        assertThatNoException().isThrownBy(() -> service.update(1L, request));
        verify(urlRepository).save(entity);
    }

    // --- delete() ---

    @Test
    void delete_redisDeleteThrows_deleteStillSucceeds() {
        Url entity = urlEntity("abc", "https://example.com", null);
        when(urlRepository.findById(1L)).thenReturn(Optional.of(entity));
        doNothing().when(urlRepository).delete(entity);
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(redisTemplate).delete(anyString());

        assertThatNoException().isThrownBy(() -> service.delete(1L));
        verify(urlRepository).delete(entity);
    }

    // --- helpers ---

    private Url urlEntity(String shortCode, String originalUrl, LocalDateTime expiredAt) {
        Url url = new Url();
        url.setShortCode(shortCode);
        url.setOriginalUrl(originalUrl);
        url.setExpiredAt(expiredAt);
        return url;
    }
}
```

- [ ] **Step 2: Run the new tests and verify all pass**

```bash
./mvnw test -Dtest=UrlServiceImplTest -q
```
Expected output ends with `BUILD SUCCESS` and `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/hhh/url/shorter_url/service/impl/UrlServiceImplTest.java
git commit -m "test(cache): add unit tests for Redis fallback in UrlServiceImpl"
```

---

### Task 7: Full test suite + manual smoke test

- [ ] **Step 1: Run the full test suite**

```bash
./mvnw test -q
```
Expected: BUILD SUCCESS, all existing tests still pass.

- [ ] **Step 2: Manual smoke test with Redis down**

```bash
# Start the app with Redis running — baseline
docker-compose up -d redis
./mvnw spring-boot:run &

# Confirm redirect works normally (replace <shortCode> with a real code in your DB)
curl -v "http://localhost:8080/api/v1/urls/redirect?shortCode=<shortCode>"
# Expected: 200 with originalUrl

# Stop Redis
docker-compose stop redis

# Hit redirect again — should still return 200, WARN should appear in app logs
curl -v "http://localhost:8080/api/v1/urls/redirect?shortCode=<shortCode>"
# Expected: 200 with originalUrl
# App log should contain: "Redis read failed, falling back to DB"

# Restart Redis
docker-compose start redis

# Hit redirect again — should repopulate cache
curl -v "http://localhost:8080/api/v1/urls/redirect?shortCode=<shortCode>"
# Expected: 200 with originalUrl, no WARN in logs this time
```
