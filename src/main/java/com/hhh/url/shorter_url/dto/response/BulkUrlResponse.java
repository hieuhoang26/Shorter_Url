package com.hhh.url.shorter_url.dto.response;

public record BulkUrlResponse(
        String shortCode,
        String domain,
        String originalUrl
) {
}
