package com.hhh.url.shorter_url.service.impl;

import com.hhh.url.shorter_url.dto.response.BatchStatusResponse;
import com.hhh.url.shorter_url.exception.BadRequestException;
import com.hhh.url.shorter_url.exception.ResourceNotFoundException;
import com.hhh.url.shorter_url.model.batch.UrlFileBatches;
import com.hhh.url.shorter_url.repository.UrlFileBatchRepository;
import com.hhh.url.shorter_url.service.BulkUrlService;
import com.hhh.url.shorter_url.service.ObjectStorageService;
import com.hhh.url.shorter_url.util.BatchStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BulkUrlServiceImpl implements BulkUrlService {

    private final UrlFileBatchRepository batchRepository;
    private final ObjectStorageService objectStorageService;
    private final JobLauncher jobLauncher;
    private final Job urlImportJob;

    @Override
    @Transactional
    public UUID createBatch(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Uploaded file must not be empty");
        }

        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "upload.xlsx";

        // Step 1 — save batch with PENDING status (get UUID first)
        UrlFileBatches batch = UrlFileBatches.builder()
                .fileName(originalFilename)
                .filePath("")
                .status(BatchStatus.PENDING)
                .totalRecords(0)
                .processedRecords(0)
                .successRecords(0)
                .failedRecords(0)
                .build();
        batch = batchRepository.save(batch);

        // Step 2 — upload file to S3 under the batch-scoped key
        String objectStoragePath = "batches/" + batch.getId() + "/" + originalFilename;
        try {
            objectStorageService.uploadObject(objectStoragePath, file.getBytes());
        } catch (IOException e) {
            throw new BadRequestException("Failed to read uploaded file: " + e.getMessage());
        }
        batch.setFilePath(objectStoragePath);
        batchRepository.save(batch);

        // Step 3 — launch async Spring Batch job
        UUID batchId = batch.getId();
        JobParameters params = new JobParametersBuilder()
                .addString("objectStoragePath", objectStoragePath)
                .addString("batchId", batchId.toString())
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        try {
            jobLauncher.run(urlImportJob, params);
        } catch (Exception e) {
            throw new BadRequestException("Failed to launch batch job: " + e.getMessage());
        }

        log.info("Batch {} created — file='{}', job launched", batchId, originalFilename);
        return batchId;
    }

    @Override
    @Transactional(readOnly = true)
    public BatchStatusResponse getBatchStatus(UUID batchId) {
        UrlFileBatches batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        return new BatchStatusResponse(
                batch.getStatus().name(),
                batch.getProcessedRecords(),
                batch.getSuccessRecords(),
                batch.getFailedRecords()
        );
    }
}
