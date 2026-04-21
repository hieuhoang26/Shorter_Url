package com.hhh.url.shorter_url.controller;

import com.hhh.url.shorter_url.common.ApiResponse;
import com.hhh.url.shorter_url.dto.request.UrlRequest;
import com.hhh.url.shorter_url.dto.response.TemplateFileResponse;
import com.hhh.url.shorter_url.dto.response.UrlResponse;
import com.hhh.url.shorter_url.service.UrlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/urls")
@RequiredArgsConstructor
public class UrlController {

    private final UrlService urlService;

    @PostMapping
    public ResponseEntity<ApiResponse<UrlResponse>> createUrl(@Valid @RequestBody UrlRequest request) {
        UrlResponse data = urlService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(data, "Url created successfully"));
    }
    @GetMapping("/template")
    public ResponseEntity<ApiResponse<TemplateFileResponse>> getTemplate(){
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponse.success(urlService.getTemplate()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UrlResponse>> getById(@PathVariable long id) {
        UrlResponse data = urlService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<UrlResponse>>> getAll(@PageableDefault(size = 20) Pageable pageable) {
        Page<UrlResponse> data = urlService.getAll(pageable);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UrlResponse>> update(@PathVariable long id, @Valid @RequestBody UrlRequest request) {
        UrlResponse data = urlService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(data, "Url updated successfully"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long id) {
        urlService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Url deleted successfully"));
    }
}
