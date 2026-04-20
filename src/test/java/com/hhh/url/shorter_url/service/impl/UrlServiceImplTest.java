package com.hhh.url.shorter_url.service.impl;

import com.hhh.url.shorter_url.dto.cache.UrlCacheEntry;
import com.hhh.url.shorter_url.dto.request.UrlRequest;
import com.hhh.url.shorter_url.dto.response.UrlResponse;
import com.hhh.url.shorter_url.exception.ResourceNotFoundException;
import com.hhh.url.shorter_url.exception.UrlExpiredException;
import com.hhh.url.shorter_url.mapper.UrlMapper;
import com.hhh.url.shorter_url.model.Url;
import com.hhh.url.shorter_url.repository.UrlRepository;
import com.hhh.url.shorter_url.service.Base62Service;
import com.hhh.url.shorter_url.service.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.hhh.url.shorter_url.util.CacheKeyConstant.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceImplTest {

    @Mock private UrlRepository urlRepository;
    @Mock private Base62Service base62Service;
    @Mock private UrlMapper urlMapper;
    @Mock private ObjectStorageService objectStorageService;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    private UrlServiceImpl urlService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        urlService = new UrlServiceImpl(urlRepository, base62Service, urlMapper, objectStorageService, redisTemplate);
    }

    // ─── redirect() ──────────────────────────────────────────────────────────

    @Test
    void redirect_cacheHit_validEntry_returnsOriginalUrl() {
        UrlCacheEntry entry = new UrlCacheEntry("https://example.com", null);
        when(valueOperations.get("short:url:abc")).thenReturn(entry);

        String result = urlService.redirect("abc");

        assertThat(result).isEqualTo("https://example.com");
        verifyNoInteractions(urlRepository);
    }

    @Test
    void redirect_cacheHit_nullSentinel_throwsResourceNotFoundException() {
        when(valueOperations.get("short:url:abc")).thenReturn(NULL_SENTINEL);

        assertThatThrownBy(() -> urlService.redirect("abc"))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(urlRepository);
    }

    @Test
    void redirect_cacheHit_expiredEntry_throwsUrlExpiredException() {
        UrlCacheEntry entry = new UrlCacheEntry("https://example.com", LocalDateTime.now().minusHours(1));
        when(valueOperations.get("short:url:abc")).thenReturn(entry);

        assertThatThrownBy(() -> urlService.redirect("abc"))
                .isInstanceOf(UrlExpiredException.class);
        verifyNoInteractions(urlRepository);
    }

    @Test
    void redirect_cacheMiss_validLink_returnsUrlAndCaches() {
        when(valueOperations.get("short:url:abc")).thenReturn(null);
        Url url = buildUrl("abc", "https://example.com", null);
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        String result = urlService.redirect("abc");

        assertThat(result).isEqualTo("https://example.com");
        ArgumentCaptor<UrlCacheEntry> entryCaptor = ArgumentCaptor.forClass(UrlCacheEntry.class);
        verify(valueOperations).set(eq("short:url:abc"), entryCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(entryCaptor.getValue().getOriginalUrl()).isEqualTo("https://example.com");
    }

    @Test
    void redirect_cacheMiss_futureExpiry_ttlCappedAt24h() {
        when(valueOperations.get("short:url:abc")).thenReturn(null);
        LocalDateTime farFuture = LocalDateTime.now().plusDays(30);
        Url url = buildUrl("abc", "https://example.com", farFuture);
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        urlService.redirect("abc");

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(eq("short:url:abc"), any(), ttlCaptor.capture(), eq(TimeUnit.SECONDS));
        assertThat(ttlCaptor.getValue()).isEqualTo(DEFAULT_TTL_HOURS * 3600);
    }

    @Test
    void redirect_cacheMiss_nearExpiry_ttlMatchesRemaining() {
        when(valueOperations.get("short:url:abc")).thenReturn(null);
        LocalDateTime soon = LocalDateTime.now().plusHours(1);
        Url url = buildUrl("abc", "https://example.com", soon);
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        urlService.redirect("abc");

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(eq("short:url:abc"), any(), ttlCaptor.capture(), eq(TimeUnit.SECONDS));
        // Should be ~3600 seconds (±5s tolerance)
        assertThat(ttlCaptor.getValue()).isBetween(3595L, 3605L);
    }

    @Test
    void redirect_cacheMiss_notFound_cachesNullSentinel() {
        when(valueOperations.get("short:url:abc")).thenReturn(null);
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.redirect("abc"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(valueOperations).set("short:url:abc", NULL_SENTINEL, NULL_TTL_MINUTES, TimeUnit.MINUTES);
    }

    @Test
    void redirect_cacheMiss_expiredInDb_throwsUrlExpiredAndDoesNotCache() {
        when(valueOperations.get("short:url:abc")).thenReturn(null);
        Url url = buildUrl("abc", "https://example.com", LocalDateTime.now().minusHours(1));
        when(urlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> urlService.redirect("abc"))
                .isInstanceOf(UrlExpiredException.class);
        verify(valueOperations, never()).set(any(), any(), anyLong(), any());
    }

    // ─── update() ────────────────────────────────────────────────────────────

    @Test
    void update_sameShortCode_evictsOldKey() {
        Url entity = buildUrl("abc", "https://old.com", null);
        when(urlRepository.findById(1L)).thenReturn(Optional.of(entity));
        Url saved = buildUrl("abc", "https://new.com", null);
        when(urlRepository.save(entity)).thenReturn(saved);
        UrlRequest request = new UrlRequest();
        request.setOriginalUrl("https://new.com");
        when(urlMapper.toResponse(saved)).thenReturn(new UrlResponse());

        urlService.update(1L, request);

        verify(redisTemplate).delete("short:url:abc");
        verify(redisTemplate, times(1)).delete(anyString());
    }

    @Test
    void update_shortCodeChanges_evictsBothKeys() {
        Url entity = buildUrl("oldCode", "https://old.com", null);
        when(urlRepository.findById(1L)).thenReturn(Optional.of(entity));
        Url saved = buildUrl("newAlias", "https://old.com", null);
        when(urlRepository.save(entity)).thenReturn(saved);
        UrlRequest request = new UrlRequest();
        request.setOriginalUrl("https://old.com");
        request.setCustomAlias("newAlias");
        when(urlMapper.toResponse(saved)).thenReturn(new UrlResponse());

        urlService.update(1L, request);

        verify(redisTemplate).delete("short:url:oldCode");
        verify(redisTemplate).delete("short:url:newAlias");
    }

    // ─── delete() ────────────────────────────────────────────────────────────

    @Test
    void delete_evictsCacheKey() {
        Url entity = buildUrl("abc", "https://example.com", null);
        when(urlRepository.findById(1L)).thenReturn(Optional.of(entity));

        urlService.delete(1L);

        verify(urlRepository).delete(entity);
        verify(redisTemplate).delete("short:url:abc");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Url buildUrl(String shortCode, String originalUrl, LocalDateTime expiredAt) {
        Url url = new Url();
        url.setShortCode(shortCode);
        url.setOriginalUrl(originalUrl);
        url.setExpiredAt(expiredAt);
        url.setDomain("http://localhost:8080");
        return url;
    }
}
