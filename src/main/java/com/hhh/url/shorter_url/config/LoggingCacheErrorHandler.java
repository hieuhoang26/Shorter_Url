package com.hhh.url.shorter_url.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

@Slf4j
public class LoggingCacheErrorHandler implements CacheErrorHandler {

    @Override
    public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
        log.warn("Cache GET failed [cache={}, key={}] ({}): {}", cache.getName(), key, e.getClass().getSimpleName(), e.getMessage());
    }

    @Override
    public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
        // value intentionally omitted — may contain sensitive data
        log.warn("Cache PUT failed [cache={}, key={}] ({}): {}", cache.getName(), key, e.getClass().getSimpleName(), e.getMessage());
    }

    @Override
    public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
        log.warn("Cache EVICT failed [cache={}, key={}] ({}): {}", cache.getName(), key, e.getClass().getSimpleName(), e.getMessage());
    }

    @Override
    public void handleCacheClearError(RuntimeException e, Cache cache) {
        log.warn("Cache CLEAR failed [cache={}] ({}): {}", cache.getName(), e.getClass().getSimpleName(), e.getMessage());
    }
}
