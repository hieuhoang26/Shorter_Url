package com.hhh.url.shorter_url.service.impl;

import com.hhh.url.shorter_url.dto.response.PreSignResponse;
import com.hhh.url.shorter_url.exception.ResourceNotFoundException;
import com.hhh.url.shorter_url.service.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Objects;

import static com.hhh.url.shorter_url.util.Constant.TEMPLATE_FILE;

@Service
@RequiredArgsConstructor
@Slf4j
public class ObjectStorageServiceImpl implements ObjectStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Override
    public void uploadObject(String key, byte[] content) {
        log.info("Uploading object {} to bucket {}", key, bucketName);
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(content));
    }

    @Override
    public byte[] downloadObject(String key) {
        log.info("Downloading object {} from bucket {}", key, bucketName);
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        try {
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getObjectRequest);
            return objectBytes.asByteArray();
        } catch (NoSuchKeyException e) {
            log.error("Object {} not found in bucket {}", key, bucketName);
            throw new ResourceNotFoundException("Object " + key + " not found in bucket " + bucketName);
        }
    }

    @Override
    public PreSignResponse generatePresignedUrl(String fileName, String method) {
        String key = Objects.equals(fileName,TEMPLATE_FILE)
                ? fileName
                : "file/%s".formatted(fileName);
        log.info("Generating presigned URL for object {} in bucket {} with method {}", key, bucketName, method);
        if ("PUT".equalsIgnoreCase(method)) {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .putObjectRequest(putObjectRequest)
                    .build();

            return PreSignResponse.builder()
                    .object(key)
                    .preSignUrl(s3Presigner.presignPutObject(presignRequest).url().toString())
                    .fileName(fileName)
                    .expireAt(15L)
                    .build();
        } else if ("GET".equalsIgnoreCase(method)) {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .getObjectRequest(getObjectRequest)
                    .build();

            return PreSignResponse.builder()
                    .object(key)
                    .preSignUrl(s3Presigner.presignGetObject(presignRequest).url().toString())
                    .fileName(fileName)
                    .expireAt(15L)
                    .build();
        } else {
            throw new IllegalArgumentException("Method " + method + " not supported for presigned URL");
        }
    }

    @Override
    public boolean verifyObject(String key) {
        log.info("Verifying object {} in bucket {}", key, bucketName);
        HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        try {
            s3Client.headObject(headObjectRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.error("Error verifying object {}: {}", key, e.getMessage());
            return false;
        }
    }
}
