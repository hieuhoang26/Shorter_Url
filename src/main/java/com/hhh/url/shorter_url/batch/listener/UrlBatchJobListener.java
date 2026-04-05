package com.hhh.url.shorter_url.batch.listener;

import com.hhh.url.shorter_url.model.batch.UrlFileBatches;
import com.hhh.url.shorter_url.repository.UrlFileBatchRecordRepository;
import com.hhh.url.shorter_url.repository.UrlFileBatchRepository;
import com.hhh.url.shorter_url.util.BatchStatus;
import com.hhh.url.shorter_url.util.RecordStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Updates the {@link UrlFileBatches} record after the Spring Batch job finishes.
 *
 * <p>Sets {@code totalRecords} from Spring Batch's {@code readCount},
 * counts SUCCESS/FAILED records from the DB, and resolves the final
 * {@link BatchStatus} (COMPLETED, PARTIAL_SUCCESS, or FAILED).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrlBatchJobListener implements JobExecutionListener {

    private final UrlFileBatchRepository batchRepository;
    private final UrlFileBatchRecordRepository recordRepository;

    @Override
    public void afterJob(JobExecution jobExecution) {
        String batchIdStr = jobExecution.getJobParameters().getString("batchId");
        if (batchIdStr == null) {
            log.error("afterJob — missing 'batchId' job parameter, cannot update batch status");
            return;
        }

        UUID batchId = UUID.fromString(batchIdStr);
        UrlFileBatches batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            log.error("afterJob — UrlFileBatch {} not found in DB", batchId);
            return;
        }

        long successCount = recordRepository.countByBatch_IdAndStatus(batchId, RecordStatus.SUCCESS);
        long failedCount = recordRepository.countByBatch_IdAndStatus(batchId, RecordStatus.FAILED);

        int readCount = jobExecution.getStepExecutions().stream()
                .mapToLong(StepExecution::getReadCount)
                .mapToInt(Math::toIntExact)
                .sum();

        batch.setTotalRecords(readCount);
        batch.setProcessedRecords((int) (successCount + failedCount));
        batch.setSuccessRecords((int) successCount);
        batch.setFailedRecords((int) failedCount);
        batch.setCompletedAt(OffsetDateTime.now());

        BatchStatus finalStatus = resolveFinalStatus(jobExecution, failedCount);
        batch.setStatus(finalStatus);

        batchRepository.save(batch);
        log.info("Batch {} finished — status={}, total={}, success={}, failed={}",
                batchId, finalStatus, readCount, successCount, failedCount);
    }

    private BatchStatus resolveFinalStatus(JobExecution jobExecution, long failedCount) {
        org.springframework.batch.core.BatchStatus springStatus = jobExecution.getStatus();
        if (springStatus == org.springframework.batch.core.BatchStatus.FAILED) {
            return BatchStatus.FAILED;
        }
        return failedCount > 0 ? BatchStatus.PARTIAL_SUCCESS : BatchStatus.COMPLETED;
    }
}
