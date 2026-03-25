package com.hhh.url.shorter_url.service.impl;

import com.hhh.url.shorter_url.dto.response.PreSignResponse;
import com.hhh.url.shorter_url.dto.response.TemplateFileResponse;
import com.hhh.url.shorter_url.exception.ResourceNotFoundException;
import com.hhh.url.shorter_url.dto.UrlRequest;
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
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;

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
        Url entity = new Url();
        entity.setOriginalUrl(request.getOriginalUrl());
        entity.setDomain(URL_LOCAL);
        entity.setExpiredAt(LocalDateTime.now().plus(Duration.ofDays(5)));
        urlRepository.save(entity);
        entity.setShortCode(base62Service.generateShortCode(entity.getId()));
        return urlMapper.toResponse(entity);
    }

    @Override
    public String redirect(String code) {
        Url entity = urlRepository.findByShortCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Url not found with id: " + code));
        return entity.getOriginalUrl();
    }

    @Override
    @Transactional(readOnly = true)
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
    public UrlResponse update(long id, UrlRequest request) {
        Url entity = urlRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Url not found with id: " + id));
        
        urlMapper.updateEntityFromRequest(request, entity);
        
        Url savedEntity = urlRepository.save(entity);
        return urlMapper.toResponse(savedEntity);
    }

    @Override
    @Transactional
    public void delete(long id) {
        Url entity = urlRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Url not found with id: " + id));
        urlRepository.delete(entity);
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
