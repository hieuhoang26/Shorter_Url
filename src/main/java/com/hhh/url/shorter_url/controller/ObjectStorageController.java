package com.hhh.url.shorter_url.controller;

import com.hhh.url.shorter_url.common.ApiResponse;
import com.hhh.url.shorter_url.dto.request.PresignedUrlRequest;
import com.hhh.url.shorter_url.dto.response.PreSignResponse;
import com.hhh.url.shorter_url.service.ObjectStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
