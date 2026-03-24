package com.hhh.url.shorter_url.dto;

public record BulkUrlResponse(
        String shortCode,
        String domain,
        String originalUrl
) {
}
