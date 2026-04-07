package com.hhh.url.shorter_url.batch.dto;

import com.hhh.url.shorter_url.model.batch.UrlFileBatches;
import com.hhh.url.shorter_url.util.RecordStatus;

import java.time.LocalDateTime;

/**
 * Immutable handoff object between {@code UrlBatchItemProcessor} and {@code UrlBatchItemWriter}.
 *
 * <p>Replaces the {@code @Transient} carrier fields that previously lived on the JPA entity
 * {@link com.hhh.url.shorter_url.model.batch.UrlFileBatchRecords}. The writer reads all data
 * it needs from this record and constructs the final JPA entities itself.
 *
 * @param rowNumber    1-based row index in the source Excel file
 * @param originalUrl  the URL to shorten (may be invalid for FAILED rows)
 * @param customAlias  optional; when non-null the writer uses this as the short code verbatim
 * @param expiredAt    optional; writer falls back to {@code now + 5 days} when null
 * @param description  optional free-text description
 * @param tags         optional comma-separated tag string
 * @param status       {@link RecordStatus#PENDING} for valid rows, {@link RecordStatus#FAILED} for invalid ones
 * @param errorMessage non-null when {@code status == FAILED}
 * @param batchRef     JPA proxy reference to the owning {@link UrlFileBatches}; never null
 */
public record ProcessedUrlRow(
        int rowNumber,
        String originalUrl,
        String customAlias,
        LocalDateTime expiredAt,
        String description,
        String tags,
        RecordStatus status,
        String errorMessage,
        UrlFileBatches batchRef
) {
}
