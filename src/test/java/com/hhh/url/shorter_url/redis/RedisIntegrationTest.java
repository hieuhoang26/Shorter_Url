package com.hhh.url.shorter_url.redis;

import com.hhh.url.shorter_url.dto.cache.UrlCacheEntry;
import com.hhh.url.shorter_url.model.Url;
import com.hhh.url.shorter_url.repository.UrlRepository;
import com.hhh.url.shorter_url.service.ObjectStorageService;
import com.hhh.url.shorter_url.service.UrlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;

import static com.hhh.url.shorter_url.util.CacheKeyConstant.shortUrlKey;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RedisIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @MockBean
    ObjectStorageService objectStorageService;

    @Autowired UrlService urlService;
    @Autowired UrlRepository urlRepository;
    @Autowired RedisTemplate<String, Object> redisTemplate;

    @AfterEach
    void tearDown() {
        urlRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void redirect_cacheMissThenCacheHit_secondCallServedFromRedis() {
        Url url = new Url();
        url.setShortCode("it-test");
        url.setOriginalUrl("https://integration-test.com");
        url.setDomain("http://localhost:8080");
        url.setExpiredAt(LocalDateTime.now().plusDays(1));
        urlRepository.save(url);

        // First call — cache miss, populates Redis
        String result1 = urlService.redirect("it-test");
        assertThat(result1).isEqualTo("https://integration-test.com");

        // Redis key should now exist
        Object cached = redisTemplate.opsForValue().get(shortUrlKey("it-test"));
        assertThat(cached).isInstanceOf(UrlCacheEntry.class);
        assertThat(((UrlCacheEntry) cached).getOriginalUrl()).isEqualTo("https://integration-test.com");

        // Second call — served from Redis (no DB query issued, same result)
        String result2 = urlService.redirect("it-test");
        assertThat(result2).isEqualTo("https://integration-test.com");
    }

    @Test
    void update_evictsCacheKey() {
        Url url = new Url();
        url.setShortCode("ev-test");
        url.setOriginalUrl("https://before-update.com");
        url.setDomain("http://localhost:8080");
        url.setExpiredAt(LocalDateTime.now().plusDays(1));
        urlRepository.save(url);

        // Populate cache
        urlService.redirect("ev-test");
        assertThat(redisTemplate.opsForValue().get(shortUrlKey("ev-test"))).isNotNull();

        // Update — should evict
        com.hhh.url.shorter_url.dto.request.UrlRequest request = new com.hhh.url.shorter_url.dto.request.UrlRequest();
        request.setOriginalUrl("https://after-update.com");
        urlService.update(url.getId(), request);

        assertThat(redisTemplate.opsForValue().get(shortUrlKey("ev-test"))).isNull();

        // Next redirect should hit DB again and re-cache new value
        String result = urlService.redirect("ev-test");
        assertThat(result).isEqualTo("https://after-update.com");
    }
}
