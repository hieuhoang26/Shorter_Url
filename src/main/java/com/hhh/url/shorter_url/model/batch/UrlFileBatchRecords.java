package com.hhh.url.shorter_url.model.batch;

import com.hhh.url.shorter_url.model.BaseEntity;
import com.hhh.url.shorter_url.util.RecordStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "url_file_batch_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrlFileBatchRecords extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "original_url", nullable = false, columnDefinition = "TEXT")
    private String originalUrl;

    @Column(name = "short_code", length = 50)
    private String shortCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RecordStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private UrlFileBatches batch;
}
