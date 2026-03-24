package com.hhh.url.shorter_url.service.impl;

import com.hhh.url.shorter_url.exception.ResourceNotFoundException;
import com.hhh.url.shorter_url.dto.UrlRequest;
import com.hhh.url.shorter_url.dto.UrlResponse;
import com.hhh.url.shorter_url.mapper.UrlMapper;
import com.hhh.url.shorter_url.model.Url;
import com.hhh.url.shorter_url.repository.UrlRepository;
import com.hhh.url.shorter_url.service.Base62Service;
import com.hhh.url.shorter_url.service.UrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UrlServiceImpl implements UrlService {

    private final UrlRepository urlRepository;
    private final Base62Service base62Service;
    private final UrlMapper urlMapper;

    private final String URL_LOCAL = "http://localhost:8080";

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
}
