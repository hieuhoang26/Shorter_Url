package com.hhh.url.shorter_url.batch.writer;

import com.hhh.url.shorter_url.batch.dto.ProcessedUrlRow;
import com.hhh.url.shorter_url.model.Url;
import com.hhh.url.shorter_url.model.batch.UrlFileBatchRecords;
import com.hhh.url.shorter_url.repository.UrlFileBatchRecordRepository;
import com.hhh.url.shorter_url.repository.UrlRepository;
import com.hhh.url.shorter_url.service.Base62Service;
import com.hhh.url.shorter_url.util.RecordStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.hhh.url.shorter_url.util.Constant.URL_LOCAL;

/**
 * Persists each processed URL record using batch DB operations.
 *
 * <p>The chunk is partitioned into FAILED rows and PENDING rows up-front, then each group is
 * written with a single {@code saveAll()} call rather than individual {@code save()} per item.
 * This reduces DB round-trips from {@code 4 × N} to a constant 4 batch operations per chunk.
 *
 * <p>Per-chunk write sequence:
 * <ol>
 *   <li>Persist all FAILED batch records in one {@code saveAll()} call.</li>
 *   <li>Insert all valid {@link Url} rows (no short code yet) — flush assigns DB-generated IDs.</li>
 *   <li>Generate short codes from IDs (or use custom alias) and update all URLs in one {@code saveAll()}.</li>
 *   <li>Persist all SUCCESS batch records in one {@code saveAll()} call.</li>
 * </ol>
 *
 * <p>Exception handling is delegated to the Spring Batch {@code faultTolerant().skip()} step
 * configuration; no per-record try-catch is used here.
 */
@Slf4j
public class UrlBatchItemWriter implements ItemWriter<ProcessedUrlRow> {

    private final UrlRepository urlRepository;
    private final UrlFileBatchRecordRepository recordRepository;
    private final Base62Service base62Service;

    public UrlBatchItemWriter(UrlRepository urlRepository,
                               UrlFileBatchRecordRepository recordRepository,
                               Base62Service base62Service) {
        this.urlRepository = urlRepository;
        this.recordRepository = recordRepository;
        this.base62Service = base62Service;
    }

    @Override
    public void write(Chunk<? extends ProcessedUrlRow> chunk) {
        List<ProcessedUrlRow> failedRows = new ArrayList<>();
        List<ProcessedUrlRow> pendingRows = new ArrayList<>();

        for (ProcessedUrlRow row : chunk.getItems()) {
            if (row.status() == RecordStatus.FAILED) {
                failedRows.add(row);
            } else {
                pendingRows.add(row);
            }
        }

        persistFailedRecords(failedRows);
        persistPendingRecords(pendingRows);
    }

    /**
     * Saves all pre-validated FAILED records in a single batch operation.
     */
    private void persistFailedRecords(List<ProcessedUrlRow> failedRows) {
        if (failedRows.isEmpty()) {
            return;
        }
        List<UrlFileBatchRecords> entities = failedRows.stream()
                .map(row -> UrlFileBatchRecords.builder()
                        .batch(row.batchRef())
                        .rowNumber(row.rowNumber())
                        .originalUrl(row.originalUrl())
                        .status(RecordStatus.FAILED)
                        .errorMessage(row.errorMessage())
                        .processedAt(OffsetDateTime.now())
                        .build())
                .toList();
        recordRepository.saveAll(entities);
        log.debug("Persisted {} FAILED records", entities.size());
    }

    /**
     * Inserts URLs and their batch records for all PENDING rows.
     *
     * <p>Uses {@code saveAllAndFlush} for the initial URL insert so that the DB-assigned
     * {@code id} is available immediately for Base62 short code generation.
     */
    private void persistPendingRecords(List<ProcessedUrlRow> pendingRows) {
        if (pendingRows.isEmpty()) {
            return;
        }

        // Step 1 — insert URLs without short codes to obtain DB-assigned IDs
        List<Url> urls = pendingRows.stream()
                .map(row -> {
                    Url url = new Url();
                    url.setOriginalUrl(row.originalUrl());
                    url.setDomain(URL_LOCAL);
                    url.setExpiredAt(row.expiredAt() != null
                            ? row.expiredAt()
                            : LocalDateTime.now().plus(Duration.ofDays(5)));
                    url.setDescription(row.description());
                    url.setTags(row.tags());
                    return url;
                })
                .toList();

        urlRepository.saveAllAndFlush(urls);  // flush assigns IDs

        // Step 2 — generate / assign short codes, then batch-update
        for (int i = 0; i < pendingRows.size(); i++) {
            ProcessedUrlRow row = pendingRows.get(i);
            Url url = urls.get(i);
            String shortCode = (row.customAlias() != null && !row.customAlias().isBlank())
                    ? row.customAlias()
                    : base62Service.generateShortCode(url.getId());
            url.setShortCode(shortCode);
        }
        urlRepository.saveAll(urls);

        // Step 3 — persist SUCCESS batch records
        OffsetDateTime now = OffsetDateTime.now();
        List<UrlFileBatchRecords> successEntities = new ArrayList<>(pendingRows.size());
        for (int i = 0; i < pendingRows.size(); i++) {
            ProcessedUrlRow row = pendingRows.get(i);
            successEntities.add(UrlFileBatchRecords.builder()
                    .batch(row.batchRef())
                    .rowNumber(row.rowNumber())
                    .originalUrl(row.originalUrl())
                    .shortCode(urls.get(i).getShortCode())
                    .status(RecordStatus.SUCCESS)
                    .processedAt(now)
                    .build());
        }
        recordRepository.saveAll(successEntities);
        log.debug("Persisted {} SUCCESS records", successEntities.size());
    }
}
