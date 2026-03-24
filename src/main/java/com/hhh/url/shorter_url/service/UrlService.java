package com.hhh.url.shorter_url.service;

import com.hhh.url.shorter_url.dto.UrlRequest;
import com.hhh.url.shorter_url.dto.UrlResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UrlService {
    UrlResponse create(UrlRequest request);

    String redirect(String code);
    UrlResponse getById(long id);
    Page<UrlResponse> getAll(Pageable pageable);
    UrlResponse update(long id, UrlRequest request);
    void delete(long id);
}
