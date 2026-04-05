package com.hhh.url.shorter_url.batch.reader;

import com.hhh.url.shorter_url.batch.dto.UrlRowDTO;
import com.hhh.url.shorter_url.service.ObjectStorageService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * Spring Batch {@code ItemReader} for URL import XLSX files.
 *
 * <p>Downloads the file from object storage using the {@code objectStoragePath} job parameter,
 * then delegates row iteration and mapping to the parent {@link PoiReader}.
 *
 * <p>Instantiated as a {@code @StepScope} bean in {@code UrlBatchJobConfig}; job parameters
 * are injected via SPEL at step-execution time.
 */
public class UrlExcelItemReader extends PoiReader<UrlRowDTO> {

    private final ObjectStorageService objectStorageService;
    private final String objectStoragePath;

    public UrlExcelItemReader(ObjectStorageService objectStorageService,
                               String objectStoragePath) {
        this.objectStorageService = objectStorageService;
        this.objectStoragePath = objectStoragePath;
    }

    /**
     * Downloads the XLSX file bytes from object storage and wraps them as a {@link Resource}.
     */
    @Override
    protected Resource getResource() {
        byte[] bytes = objectStorageService.downloadObject(objectStoragePath);
        return new ByteArrayResource(bytes);
    }

    @Override
    protected PoiRowMapper<UrlRowDTO> getRowMapper() {
        return new UrlRowMapper();
    }
}
