package com.hhh.url.shorter_url.repository;

import com.hhh.url.shorter_url.model.batch.UrlFileBatchRecords;
import com.hhh.url.shorter_url.util.RecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UrlFileBatchRecordRepository extends JpaRepository<UrlFileBatchRecords, UUID> {

    /**
     * Counts records with a specific status for the given batch.
     * Used by {@link com.hhh.url.shorter_url.batch.listener.UrlBatchJobListener}
     * to calculate final success/failed totals after job completion.
     */
    long countByBatch_IdAndStatus(UUID batchId, RecordStatus status);
}
