package com.hhh.url.shorter_url.model.batch;

import com.hhh.url.shorter_url.model.BaseEntity;
import com.hhh.url.shorter_url.util.BatchStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "url_file_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrlFileBatches extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchStatus status;

    @Column(name = "total_records", nullable = false)
    private int totalRecords;

    @Column(name = "processed_records", nullable = false)
    private int processedRecords;

    @Column(name = "success_records", nullable = false)
    private int successRecords;

    @Column(name = "failed_records", nullable = false)
    private int failedRecords;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
