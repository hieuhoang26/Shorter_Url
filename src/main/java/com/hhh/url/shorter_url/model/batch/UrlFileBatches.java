package com.hhh.url.shorter_url.model.batch;

import com.hhh.url.shorter_url.model.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.batch.core.BatchStatus;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "url_file_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrlFileBatches extends BaseEntity{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchStatus status;

    @Column(name = "total_records", nullable = false)
    private int totalRecords;

    @Column(name = "success_records", nullable = false)
    private int successRecords;

    @Column(name = "failed_records", nullable = false)
    private int failedRecords;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UrlFileBatchRecords> records;
}
