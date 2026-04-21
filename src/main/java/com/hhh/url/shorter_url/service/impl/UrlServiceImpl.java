package com.hhh.url.shorter_url.service.impl;

import com.hhh.url.shorter_url.dto.cache.UrlCacheEntry;
import com.hhh.url.shorter_url.dto.response.PreSignResponse;
import com.hhh.url.shorter_url.dto.response.TemplateFileResponse;
import com.hhh.url.shorter_url.exception.BadRequestException;
import com.hhh.url.shorter_url.exception.ResourceNotFoundException;
import com.hhh.url.shorter_url.exception.UrlExpiredException;
import com.hhh.url.shorter_url.dto.request.UrlRequest;
import com.hhh.url.shorter_url.dto.response.UrlResponse;
import com.hhh.url.shorter_url.mapper.UrlMapper;
import com.hhh.url.shorter_url.model.Url;
import com.hhh.url.shorter_url.repository.UrlRepository;
import com.hhh.url.shorter_url.service.Base62Service;
import com.hhh.url.shorter_url.service.ObjectStorageService;
import com.hhh.url.shorter_url.service.UrlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.hhh.url.shorter_url.util.CacheKeyConstant.*;
import static com.hhh.url.shorter_url.util.Constant.TEMPLATE_FILE;
import static com.hhh.url.shorter_url.util.Constant.URL_LOCAL;


@Service
@RequiredArgsConstructor
@Slf4j
public class UrlServiceImpl implements UrlService {

    private final UrlRepository urlRepository;
    private final Base62Service base62Service;
    private final UrlMapper urlMapper;
    private final ObjectStorageService objectStorageService;
    private final RedisTemplate<String, Object> redisTemplate;


    @Value("classpath:/static/"+TEMPLATE_FILE)
    private Resource resource;

    @EventListener(ApplicationReadyEvent.class)
    public void initTempate(){
        if(!resource.exists()){
            log.warn("Template file {} does not exist",resource);
            return;
        }
        try {
            byte[] localContent = resource.getInputStream().readAllBytes();
            boolean hasUploaded = objectStorageService.verifyObject(TEMPLATE_FILE);

            if(!hasUploaded){
                log.info("Template file not found in storage, uploading...");
                objectStorageService.uploadObject(TEMPLATE_FILE, localContent);
            } else {
                byte[] remoteContent = objectStorageService.downloadObject(TEMPLATE_FILE);
                log.info("Template file downloaded...");
                if (!MessageDigest.isEqual(calculateHash(localContent), calculateHash(remoteContent))) {
                    log.info("Template file changed, updating storage...");
                    objectStorageService.uploadObject(TEMPLATE_FILE, localContent);
                }
            }
        }catch (Exception e){
            log.error("Fail to initialize template file: {}", e.getMessage());
        }
    }

    private byte[] calculateHash(byte[] content) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(content);
    }

    @Override
    @Transactional
    public UrlResponse create(UrlRequest request) {
        boolean hasAlias = request.getCustomAlias() != null && !request.getCustomAlias().isBlank();

        if (!hasAlias) {
            // Case A: same originalUrl, no custom alias → reuse existing record
            Optional<Url> existing = urlRepository.findByOriginalUrlAndCustomAliasIsNull(request.getOriginalUrl());
            if (existing.isPresent()) {
                return urlMapper.toResponse(existing.get());
            }
        } else {
            // Case B: same originalUrl + same custom alias → reuse existing record
            Optional<Url> existing = urlRepository.findByOriginalUrlAndCustomAlias(
                    request.getOriginalUrl(), request.getCustomAlias());
            if (existing.isPresent()) {
                return urlMapper.toResponse(existing.get());
            }
            // Case C (different originalUrl, alias taken) → caught below by unique constraint
            // Case D (same originalUrl, new alias) → falls through to insert
        }

        Url entity = new Url();
        entity.setOriginalUrl(request.getOriginalUrl());
        entity.setDomain(URL_LOCAL);
        entity.setExpiredAt(LocalDateTime.now().plus(Duration.ofDays(5)));
        entity.setDescription(request.getDescription());
        entity.setTags(request.getTags());
        try {
            urlRepository.save(entity);
            if (hasAlias) {
                entity.setShortCode(request.getCustomAlias());
                entity.setCustomAlias(request.getCustomAlias());
            } else {
                entity.setShortCode(base62Service.generateShortCode(entity.getId()));
            }
            urlRepository.save(entity);
            return urlMapper.toResponse(entity);
        } catch (DataIntegrityViolationException ex) {
            throw new BadRequestException("Custom alias already in use");
        }
    }

    @Override
    @Cacheable(value = "url_redirect", key = "#code")
    public String redirect(String code) {
        String key = shortUrlKey(code);

        Object cached = null;
        try {
            cached = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis read failed, falling back to DB [{}]: {}", e.getClass().getSimpleName(), e.getMessage());
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
                log.warn("Redis write failed, null sentinel not cached [{}]: {}", e.getClass().getSimpleName(), e.getMessage());
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
            log.warn("Redis write failed, cache not populated [{}]: {}", e.getClass().getSimpleName(), e.getMessage());
        }

        return entity.getOriginalUrl();
    }

    private Duration computeTtl(LocalDateTime expiredAt) {
        if (expiredAt == null) {
            return Duration.ofHours(DEFAULT_TTL_HOURS);
        }
        Duration remaining = Duration.between(LocalDateTime.now(), expiredAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ofSeconds(1);
        }
        return remaining.compareTo(Duration.ofHours(DEFAULT_TTL_HOURS)) < 0
                ? remaining
                : Duration.ofHours(DEFAULT_TTL_HOURS);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "url_details", key = "#id")
    public UrlResponse getById(long id) {
        Url entity = urlRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Url not found with id: " + id));
        return urlMapper.toResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UrlResponse> getAll(Pageable pageable) {
        return urlRepository.findAll(pageable)
                .map(urlMapper::toResponse);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "url_details", key = "#id"),
            @CacheEvict(value = "url_redirect", allEntries = true)
    })
    public UrlResponse update(long id, UrlRequest request) {
        Url entity = urlRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Url not found with id: " + id));
        String oldShortCode = entity.getShortCode();
        urlMapper.updateEntityFromRequest(request, entity);
        if (request.getCustomAlias() != null && !request.getCustomAlias().isBlank()) {
            entity.setShortCode(request.getCustomAlias());
        }
        try {
            Url savedEntity = urlRepository.save(entity);
            try {
                redisTemplate.delete(shortUrlKey(oldShortCode));
                if (!oldShortCode.equals(savedEntity.getShortCode())) {
                    redisTemplate.delete(shortUrlKey(savedEntity.getShortCode()));
                }
            } catch (Exception e) {
                log.warn("Redis delete failed during update, stale entry may remain [{}]: {}", e.getClass().getSimpleName(), e.getMessage());
            }
            return urlMapper.toResponse(savedEntity);
        } catch (DataIntegrityViolationException ex) {
            throw new BadRequestException("Custom alias already in use");
        }
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "url_details", key = "#id"),
            @CacheEvict(value = "url_redirect", allEntries = true)
    })
    public void delete(long id) {
        Url entity = urlRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Url not found with id: " + id));
        String shortCode = entity.getShortCode();
        urlRepository.delete(entity);
        try {
            redisTemplate.delete(shortUrlKey(shortCode));
        } catch (Exception e) {
            log.warn("Redis delete failed during delete, stale entry may remain [{}]: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    @Override
    public TemplateFileResponse getTemplate() {
        PreSignResponse response = objectStorageService.generatePresignedUrl(TEMPLATE_FILE,"GET");
        return TemplateFileResponse.builder()
                .preSignUrl(response.getPreSignUrl())
                .fileName(TEMPLATE_FILE)
                .description("Template for import bulk link")
                .expireAt(response.getExpireAt())
                .build();
    }
}
