package com.hhh.url.shorter_url.controller;

import com.hhh.url.shorter_url.common.ApiResponse;
import com.hhh.url.shorter_url.dto.PresignedUrlRequest;
import com.hhh.url.shorter_url.dto.response.PreSignResponse;
import com.hhh.url.shorter_url.service.ObjectStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class ObjectStorageController {

    private final ObjectStorageService objectStorageService;

    @PostMapping("/presigned-url")
    public ResponseEntity<ApiResponse<PreSignResponse>> generatePresignedUrl(@Valid @RequestBody PresignedUrlRequest request) {
        PreSignResponse url = objectStorageService.generatePresignedUrl(
                request.getFileName(),
                request.getMethod());
        return ResponseEntity.ok(ApiResponse.success(url));
    }

    @GetMapping("/verify")
    public ResponseEntity<ApiResponse<Boolean>> verifyObject(
            @RequestParam("bucketName") String bucketName,
            @RequestParam("key") String key) {
        boolean exists = objectStorageService.verifyObject(key);
        return ResponseEntity.ok(ApiResponse.success(exists));
    }
}
