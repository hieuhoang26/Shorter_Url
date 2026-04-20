# Redis Integration Guide (AI-Optimized)

### For Short URL Service (Spring Boot)

Includes Docker Compose setup

---

# 1. Goal

Integrate Redis into your system to:

* Reduce DB load (read-heavy system)
* Improve redirect latency
* Handle hot keys efficiently
* Prepare for scale

This document focuses on **practical + production-ready patterns**.

---

# 2. Architecture Overview

```
Client → App → Redis → DB
```

### Redirect Flow (Critical Path)

```
GET /{shortCode}
    ↓
Redis (cache)
    ↓ miss
Database
    ↓
Cache result
    ↓
Redirect
```

---

# 3. Run Redis with Docker Compose

## docker-compose.yml

```yaml
version: '3.8'

services:
  redis:
    image: redis:7
    container_name: redis-short-url
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data
    restart: always

volumes:
  redis_data:
```

---

## Run Redis

```bash
docker-compose up -d
```

Check:

```bash
docker ps
```

---

## Connect Redis CLI

```bash
docker exec -it redis-short-url redis-cli
```

---

# 4. Spring Boot Integration

## 4.1 Dependencies

**Maven**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

---

## 4.2 application.yml

```yaml
spring:
  redis:
    host: localhost
    port: 6379
    timeout: 2000
```

---

## 4.3 Redis Configuration

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory();
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory) {

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer();

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);

        return template;
    }
}
```

---

# 5. Cache Design

## 5.1 Key Design

```
short:url:{shortCode}
```

Example:

```
short:url:abc123
```

---

## 5.2 Value

```json
{
  "originalUrl": "https://example.com",
  "expiredAt": "2026-12-01T00:00:00"
}
```

---

## 5.3 TTL Strategy

| Type          | TTL               |
| ------------- | ----------------- |
| Normal link   | 24h               |
| Hot link      | longer (optional) |
| Expiring link | match expiredAt   |

---

# 6. Service Implementation

## 6.1 Read (Redirect)

```java
public String getOriginalUrl(String shortCode) {

    String key = "short:url:" + shortCode;

    // 1. Check cache
    String cached = redisTemplate.opsForValue().get(key);
    if (cached != null) {
        return cached;
    }

    // 2. DB fallback
    Url url = urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new NotFoundException());

    // 3. Cache result
    redisTemplate.opsForValue()
            .set(key, url.getOriginalUrl(), Duration.ofHours(24));

    return url.getOriginalUrl();
}
```

---

## 6.2 Create (Lazy Cache)

```java
public String create(String originalUrl) {

    Url url = saveToDb(originalUrl);

    // DO NOT cache here (lazy loading)

    return url.getShortCode();
}
```

---

## 6.3 Update

```java
public void update(String shortCode, String newUrl) {

    // 1. Update DB
    urlRepository.update(shortCode, newUrl);

    // 2. Delete cache
    redisTemplate.delete("short:url:" + shortCode);
}
```

---

## 6.4 Delete

```java
public void delete(String shortCode) {

    // 1. Delete DB
    urlRepository.delete(shortCode);

    // 2. Delete cache
    redisTemplate.delete("short:url:" + shortCode);
}
```

---

# 7. Advanced Optimizations (Important for AI/Scale)

[//]: # ()
[//]: # (## 7.1 Cache Breakdown Protection)

[//]: # ()
[//]: # (Problem:)

[//]: # ()
[//]: # (* Many requests hit DB when cache expires)

[//]: # ()
[//]: # (Solution:)

[//]: # ()
[//]: # (```java)

[//]: # (SETNX lock:shortCode)

[//]: # (```)

[//]: # ()
[//]: # (Or use:)

[//]: # ()
[//]: # (* Redisson lock)

[//]: # (* or simple synchronized fallback)

[//]: # ()
[//]: # (---)

[//]: # ()
[//]: # (## 7.2 Hot Key Handling)

[//]: # ()
[//]: # (For viral links:)

[//]: # ()
[//]: # (* Add **local cache &#40;Caffeine&#41;**)

[//]: # ()
[//]: # (Flow:)

[//]: # ()
[//]: # (```)

[//]: # (Caffeine → Redis → DB)

[//]: # (```)

[//]: # ()
[//]: # (---)

## 7.3 Cache Penetration

Invalid shortCode spam:

Solution:

* cache NULL value (short TTL)

```java
if (url == null) {
    redisTemplate.opsForValue().set(key, "NULL", 5 min);
}
```

---

[//]: # ()
[//]: # (## 7.4 Circuit Breaker &#40;Redis fail&#41;)

[//]: # ()
[//]: # (Use:)

[//]: # ()
[//]: # (* Resilience4j)

[//]: # ()
[//]: # (Fallback:)

[//]: # ()
[//]: # (```)

[//]: # (Redis down → direct DB)

[//]: # (```)

[//]: # ()
[//]: # (---)

[//]: # (# 8. Monitoring)

[//]: # ()
[//]: # (Track:)

[//]: # ()
[//]: # (* cache hit rate)

[//]: # (* Redis latency)

[//]: # (* Redis memory usage)

[//]: # ()
[//]: # (Tools:)

[//]: # ()
[//]: # (* Prometheus)

[//]: # (* Grafana)

[//]: # ()
[//]: # (---)

[//]: # (# 9. Production Notes)

[//]: # ()
[//]: # (## Do)

[//]: # ()
[//]: # (* Use Redis cluster when scale grows)

[//]: # (* Set maxmemory policy:)

[//]: # ()
[//]: # (```bash)

[//]: # (maxmemory-policy allkeys-lru)

[//]: # (```)

[//]: # ()
[//]: # (---)

## Don’t

* Don’t store huge objects
* Don’t use infinite TTL blindly
* Don’t skip cache invalidation

---

# 10. Summary

### Core Rules

* Read → Redis first
* Cache miss → DB → cache
* Create → DB only
* Update/Delete → DB first → delete cache

---

### Why this works

* Simple
* Scalable
* Consistent enough for your use case
* Perfect for read-heavy systems
