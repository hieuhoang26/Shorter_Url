package com.hhh.url.shorter_url.controller;

import com.hhh.url.shorter_url.common.ApiResponse;
import com.hhh.url.shorter_url.service.UrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final UrlService urlService;

    @GetMapping("/{shortCode}")
    public ResponseEntity<ApiResponse<String>> redirect(@PathVariable String shortCode) {
        String originalUrl = urlService.redirect(shortCode);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponse.success(originalUrl, "Redirect url successfully"));
    }
}
