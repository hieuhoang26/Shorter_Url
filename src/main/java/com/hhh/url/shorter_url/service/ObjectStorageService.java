package com.hhh.url.shorter_url.service;

import com.hhh.url.shorter_url.dto.response.PreSignResponse;

public interface ObjectStorageService {
    /**
     * Upload an object to S3.
     */
    void uploadObject(String key, byte[] content);

    /**
     * Download an object from S3.
     */
    byte[] downloadObject(String key);

    /**
     * Generate a presigned URL for GET or PUT operation.
     */
    PreSignResponse generatePresignedUrl(String filename, String method);

    /**
     * Verify if an object exists in S3.
     */
    boolean verifyObject(String key);
}
