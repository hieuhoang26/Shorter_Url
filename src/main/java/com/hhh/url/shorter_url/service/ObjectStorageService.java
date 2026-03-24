package com.hhh.url.shorter_url.service;

import java.io.InputStream;
import java.time.Duration;

public interface ObjectStorageService {
    /**
     * Upload an object to S3.
     */
    void uploadObject(String bucketName, String key, byte[] content);

    /**
     * Download an object from S3.
     */
    byte[] downloadObject(String bucketName, String key);

    /**
     * Generate a presigned URL for GET or PUT operation.
     */
    String generatePresignedUrl(String bucketName, String key, String method, Duration expiration);

    /**
     * Verify if an object exists in S3.
     */
    boolean verifyObject(String bucketName, String key);
}
