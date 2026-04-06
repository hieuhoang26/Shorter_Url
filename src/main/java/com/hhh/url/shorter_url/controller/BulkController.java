package com.hhh.url.shorter_url.controller;

import com.hhh.url.shorter_url.common.ApiResponse;
import com.hhh.url.shorter_url.dto.request.ImportFileRequest;
import com.hhh.url.shorter_url.dto.response.BatchStatusResponse;
import com.hhh.url.shorter_url.dto.response.BulkUploadResponse;
import com.hhh.url.shorter_url.service.BulkUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bulk")
@RequiredArgsConstructor
public class BulkController {

    private final BulkUrlService bulkUrlService;

    /**
     * Accepts a CSV file, creates a batch, and triggers async processing.
     *
     * @param request multipart CSV file with original_url column
     * @return 202 Accepted with the generated batchId
     */
    @PostMapping
    public ResponseEntity<ApiResponse<BulkUploadResponse>> uploadBatch(
            @RequestBody ImportFileRequest request) {
        UUID batchId = bulkUrlService.createBatch(request);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(new BulkUploadResponse(batchId), "Batch created successfully"));
    }

    /**
     * Returns the current status and progress counters for a batch.
     *
     * @param batchId UUID of the batch
     * @return 200 OK with status, progress, success and failed counts
     */
    @GetMapping("/{batchId}")
    public ResponseEntity<ApiResponse<BatchStatusResponse>> getBatchStatus(
            @PathVariable UUID batchId) {
        BatchStatusResponse response = bulkUrlService.getBatchStatus(batchId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
