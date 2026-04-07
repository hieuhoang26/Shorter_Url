package com.hhh.url.shorter_url.batch.reader;

import com.hhh.url.shorter_url.batch.dto.UrlRowDTO;
import com.hhh.url.shorter_url.batch.mapper.PoiRowMapper;
import com.hhh.url.shorter_url.batch.mapper.UrlRowMapper;
import com.hhh.url.shorter_url.service.ObjectStorageService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * Spring Batch {@code ItemReader} for URL import XLSX files.
 *
 * <p>Downloads the file from object storage using the {@code objectStoragePath} job parameter,
 * then delegates row iteration and mapping to the parent {@link PoiReader}.
 *
 * <p>The file bytes are downloaded once and cached in a field so that job restarts (which call
 * {@link #open} again) do not trigger a redundant S3 download.
 *
 * <p>Instantiated as a {@code @StepScope} bean in {@code UrlBatchJobConfig}; job parameters
 * are injected via SpEL at step-execution time.
 */
public class UrlExcelItemReader extends PoiReader<UrlRowDTO> {

    private final ObjectStorageService objectStorageService;
    private final String objectStoragePath;
    private byte[] cachedBytes;

    public UrlExcelItemReader(ObjectStorageService objectStorageService,
                               String objectStoragePath) {
        this.objectStorageService = objectStorageService;
        this.objectStoragePath = objectStoragePath;
    }

    /**
     * Returns the XLSX file as a {@link Resource}, downloading from object storage on the first
     * call and serving the cached bytes on subsequent calls (e.g. job restart).
     */
    @Override
    protected Resource getResource() {
        if (cachedBytes == null) {
            cachedBytes = objectStorageService.downloadObject(objectStoragePath);
        }
        return new ByteArrayResource(cachedBytes);
    }

    @Override
    protected PoiRowMapper<UrlRowDTO> getRowMapper() {
        return new UrlRowMapper();
    }
}
