package com.hhh.url.shorter_url.batch.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Represents a single parsed row from the uploaded XLSX file.
 *
 * <p>Column layout:
 * <ol>
 *   <li>col 0 — {@code original_url} (required)</li>
 *   <li>col 1 — {@code custom_alias} (optional; used as shortCode instead of Base62)</li>
 *   <li>col 2 — {@code expired_at} (optional; falls back to now + 5 days)</li>
 *   <li>col 3 — {@code description} (optional)</li>
 *   <li>col 4 — {@code tags} (optional; comma-separated)</li>
 * </ol>
 */
@Getter
@Builder
public class UrlRowDTO {

    private final int rowNumber;
    private final String originalUrl;
    private final String customAlias;
    private final LocalDateTime expiredAt;
    private final String description;
    private final String tags;
}
