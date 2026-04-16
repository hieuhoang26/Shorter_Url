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
import java.util.Map;
import java.util.stream.Collectors;

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
     * Applies upsert logic (cases A–D from the cheatsheet) then inserts only truly new URLs.
     *
     * <p>Two bulk lookups are issued per chunk regardless of chunk size:
     * <ol>
     *   <li>Find existing auto-generated records by {@code originalUrl} (Case A).</li>
     *   <li>Find existing records by {@code customAlias} (Cases B / C).</li>
     * </ol>
     *
     * <p>Classification:
     * <ul>
     *   <li><b>Case A</b>: same {@code originalUrl}, no alias → reuse existing record.</li>
     *   <li><b>Case B</b>: same {@code originalUrl} + same alias → reuse existing record.</li>
     *   <li><b>Case C</b>: different {@code originalUrl}, alias already taken → FAILED.</li>
     *   <li><b>Case D / new</b>: everything else → insert new URL.</li>
     * </ul>
     */
    private void persistPendingRecords(List<ProcessedUrlRow> pendingRows) {
        if (pendingRows.isEmpty()) {
            return;
        }

        // ── Bulk lookups ─────────────────────────────────────────────────────────
        List<String> noAliasUrls = pendingRows.stream()
                .filter(r -> r.customAlias() == null || r.customAlias().isBlank())
                .map(ProcessedUrlRow::originalUrl)
                .distinct()
                .toList();

        List<String> aliases = pendingRows.stream()
                .filter(r -> r.customAlias() != null && !r.customAlias().isBlank())
                .map(ProcessedUrlRow::customAlias)
                .distinct()
                .toList();

        // Case A lookup: originalUrl → existing Url (no alias)
        Map<String, Url> existingByOriginalUrl = noAliasUrls.isEmpty()
                ? Map.of()
                : urlRepository.findByOriginalUrlInAndCustomAliasIsNull(noAliasUrls)
                        .stream().collect(Collectors.toMap(Url::getOriginalUrl, u -> u));

        // Case B/C lookup: customAlias → existing Url
        Map<String, Url> existingByAlias = aliases.isEmpty()
                ? Map.of()
                : urlRepository.findByCustomAliasIn(aliases)
                        .stream().collect(Collectors.toMap(Url::getCustomAlias, u -> u));

        // ── Classify rows ─────────────────────────────────────────────────────────
        List<ProcessedUrlRow> reusedRows  = new ArrayList<>();
        List<ProcessedUrlRow> caseC_failed = new ArrayList<>();
        List<ProcessedUrlRow> toInsert    = new ArrayList<>();

        for (ProcessedUrlRow row : pendingRows) {
            boolean hasAlias = row.customAlias() != null && !row.customAlias().isBlank();

            if (!hasAlias) {
                Url existing = existingByOriginalUrl.get(row.originalUrl());
                if (existing != null) {
                    reusedRows.add(row);          // Case A
                } else {
                    toInsert.add(row);
                }
            } else {
                Url existing = existingByAlias.get(row.customAlias());
                if (existing != null && existing.getOriginalUrl().equals(row.originalUrl())) {
                    reusedRows.add(row);           // Case B
                } else if (existing != null) {
                    caseC_failed.add(row);         // Case C — alias taken by different URL
                } else {
                    toInsert.add(row);             // Case D or brand new
                }
            }
        }

        OffsetDateTime now = OffsetDateTime.now();

        // ── Reused rows → SUCCESS with existing short code ────────────────────────
        if (!reusedRows.isEmpty()) {
            List<UrlFileBatchRecords> reusedRecords = reusedRows.stream()
                    .map(row -> {
                        boolean hasAlias = row.customAlias() != null && !row.customAlias().isBlank();
                        String shortCode = hasAlias
                                ? existingByAlias.get(row.customAlias()).getShortCode()
                                : existingByOriginalUrl.get(row.originalUrl()).getShortCode();
                        return UrlFileBatchRecords.builder()
                                .batch(row.batchRef())
                                .rowNumber(row.rowNumber())
                                .originalUrl(row.originalUrl())
                                .shortCode(shortCode)
                                .status(RecordStatus.SUCCESS)
                                .processedAt(now)
                                .build();
                    })
                    .toList();
            recordRepository.saveAll(reusedRecords);
            log.debug("Reused {} existing records (Case A/B)", reusedRecords.size());
        }

        // ── Case C rows → FAILED ──────────────────────────────────────────────────
        if (!caseC_failed.isEmpty()) {
            List<UrlFileBatchRecords> failedRecords = caseC_failed.stream()
                    .map(row -> UrlFileBatchRecords.builder()
                            .batch(row.batchRef())
                            .rowNumber(row.rowNumber())
                            .originalUrl(row.originalUrl())
                            .status(RecordStatus.FAILED)
                            .errorMessage("Custom alias '" + row.customAlias() + "' is already used by a different URL")
                            .processedAt(now)
                            .build())
                    .toList();
            recordRepository.saveAll(failedRecords);
            log.debug("Rejected {} rows with taken aliases (Case C)", failedRecords.size());
        }

        // ── New rows → insert ─────────────────────────────────────────────────────
        if (toInsert.isEmpty()) {
            return;
        }

        // Step 1 — insert URLs without short codes to obtain DB-assigned IDs
        List<Url> urls = toInsert.stream()
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
        for (int i = 0; i < toInsert.size(); i++) {
            ProcessedUrlRow row = toInsert.get(i);
            Url url = urls.get(i);
            String shortCode = (row.customAlias() != null && !row.customAlias().isBlank())
                    ? row.customAlias()
                    : base62Service.generateShortCode(url.getId());
            url.setShortCode(shortCode);
            if (row.customAlias() != null && !row.customAlias().isBlank()) {
                url.setCustomAlias(row.customAlias());
            }
        }
        urlRepository.saveAll(urls);

        // Step 3 — persist SUCCESS batch records for new rows
        List<UrlFileBatchRecords> successEntities = new ArrayList<>(toInsert.size());
        for (int i = 0; i < toInsert.size(); i++) {
            ProcessedUrlRow row = toInsert.get(i);
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
        log.debug("Inserted {} new URL records", successEntities.size());
    }
}
