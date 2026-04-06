package com.hhh.url.shorter_url.service;

import com.hhh.url.shorter_url.dto.request.ImportFileRequest;
import com.hhh.url.shorter_url.dto.response.BatchStatusResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface BulkUrlService {

    /**
     * Uploads the file to S3, parses CSV rows into batch records,
     * persists them as PENDING, and triggers async processing.
     *
     * @return the generated batch ID
     */
    UUID createBatch(ImportFileRequest request);

    /**
     * Returns the current status and progress counters for a batch.
     */
    BatchStatusResponse getBatchStatus(UUID batchId);
}
