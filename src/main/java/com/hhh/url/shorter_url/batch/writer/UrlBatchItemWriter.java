package com.hhh.url.shorter_url.batch.writer;

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

import static com.hhh.url.shorter_url.util.Constant.URL_LOCAL;

/**
 * Persists each processed URL record.
 *
 * <p>For {@link RecordStatus#PENDING} items (valid URL):
 * <ol>
 *   <li>Inserts a new {@link Url} row to obtain the DB-assigned {@code id}.</li>
 *   <li>Generates the Base62 short code from that id.</li>
 *   <li>Updates the {@link Url} with the short code.</li>
 *   <li>Saves the {@link UrlFileBatchRecords} as {@link RecordStatus#SUCCESS}.</li>
 * </ol>
 *
 * <p>For {@link RecordStatus#FAILED} items (invalid URL detected by the processor):
 * the record is saved as-is with the existing error message.
 */
@Slf4j
public class UrlBatchItemWriter implements ItemWriter<UrlFileBatchRecords> {

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
    public void write(Chunk<? extends UrlFileBatchRecords> chunk) {
        for (UrlFileBatchRecords record : chunk.getItems()) {
            if (record.getStatus() == RecordStatus.FAILED) {
                recordRepository.save(record);
            } else {
                writeValidRecord(record);
            }
        }
    }

    private void writeValidRecord(UrlFileBatchRecords record) {
        try {
            Url url = new Url();
            url.setOriginalUrl(record.getOriginalUrl());
            url.setDomain(URL_LOCAL);
            url.setExpiredAt(record.getExpiredAt() != null
                    ? record.getExpiredAt()
                    : LocalDateTime.now().plus(Duration.ofDays(5)));
            url.setDescription(record.getDescription());
            url.setTags(record.getTags());

            String shortCode;
            if (record.getCustomAlias() != null && !record.getCustomAlias().isBlank()) {
                url.setShortCode(record.getCustomAlias());
                urlRepository.save(url);
                shortCode = record.getCustomAlias();
            } else {
                urlRepository.save(url);
                shortCode = base62Service.generateShortCode(url.getId());
                url.setShortCode(shortCode);
                urlRepository.save(url);
            }

            record.setShortCode(shortCode);
            record.setStatus(RecordStatus.SUCCESS);
            record.setProcessedAt(OffsetDateTime.now());
            recordRepository.save(record);
        } catch (Exception e) {
            log.error("Failed to write record row={} url={}: {}",
                    record.getRowNumber(), record.getOriginalUrl(), e.getMessage());
            record.setStatus(RecordStatus.FAILED);
            record.setErrorMessage(e.getMessage());
            record.setProcessedAt(OffsetDateTime.now());
            recordRepository.save(record);
        }
    }
}
