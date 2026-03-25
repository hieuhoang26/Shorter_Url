package com.hhh.url.shorter_url.heatlh;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;

@Service
@RequiredArgsConstructor
public class S3HealthIndicator implements HealthIndicator {

    @Value("${aws.s3.bucket}")
    private String bucketName;

    private final S3Client s3Client;

    @Override
    public Health health() {
        try {
            s3Client.headBucket(b -> b.bucket(bucketName));
            return Health.up()
                    .withDetail("service","S3")
                    .withDetail("status", "OK")
                    .build();
        } catch (Exception e){
            return Health.down()
                    .withDetail("service","S3")
                    .withDetail("status", "Fail")
                    .build();
        }
    }
}
