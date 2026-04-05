package com.hhh.url.shorter_url.dto.response;

public record BatchStatusResponse(
        String status,
        int progress,
        int success,
        int failed
) {
}
