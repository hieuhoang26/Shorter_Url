package com.hhh.url.shorter_url.batch.processor;

import com.hhh.url.shorter_url.batch.dto.ProcessedUrlRow;
import com.hhh.url.shorter_url.batch.dto.UrlRowDTO;
import com.hhh.url.shorter_url.model.batch.UrlFileBatches;
import com.hhh.url.shorter_url.repository.UrlFileBatchRepository;
import com.hhh.url.shorter_url.util.RecordStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;

import java.net.URI;
import java.util.UUID;

/**
 * Validates each URL row and produces a {@link ProcessedUrlRow} ready for the writer.
 *
 * <p>Rows with an invalid or blank URL are returned with {@link RecordStatus#FAILED} so the
 * writer can persist the failure without touching the {@code url} table. Valid URLs are returned
 * with {@link RecordStatus#PENDING}; the writer completes them by inserting the short code.
 *
 * <p>The batch reference is resolved lazily on the first item via
 * {@link UrlFileBatchRepository#getReferenceById(Object)}, which returns a JPA proxy without
 * issuing a SELECT — safe across chunk transaction boundaries.
 */
@Slf4j
public class UrlBatchItemProcessor implements ItemProcessor<UrlRowDTO, ProcessedUrlRow> {

    private final UrlFileBatchRepository batchRepository;
    private final UUID batchId;
    private UrlFileBatches batchRef;

    public UrlBatchItemProcessor(UrlFileBatchRepository batchRepository, String batchId) {
        this.batchRepository = batchRepository;
        this.batchId = UUID.fromString(batchId);
    }

    @Override
    public ProcessedUrlRow process(UrlRowDTO item) {
        if (batchRef == null) {
            batchRef = batchRepository.getReferenceById(batchId);
        }

        String originalUrl = item.getOriginalUrl();

        if (!isValidUrl(originalUrl)) {
            log.warn("Row {} — invalid URL '{}', marking FAILED", item.getRowNumber(), originalUrl);
            return new ProcessedUrlRow(
                    item.getRowNumber(),
                    originalUrl != null ? originalUrl : "",
                    null,
                    null,
                    null,
                    null,
                    RecordStatus.FAILED,
                    "Invalid URL format: must be a valid http/https URL",
                    batchRef
            );
        }

        return new ProcessedUrlRow(
                item.getRowNumber(),
                originalUrl,
                item.getCustomAlias(),
                item.getExpiredAt(),
                item.getDescription(),
                item.getTags(),
                RecordStatus.PENDING,
                null,
                batchRef
        );
    }

    private boolean isValidUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
