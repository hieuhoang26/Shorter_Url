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
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tracks the lifecycle of a {@link UrlFileBatches} record throughout a Spring Batch job run.
 *
 * <p>Implements both {@link JobExecutionListener} and {@link StepExecutionListener} so it can:
 * <ul>
 *   <li>{@code beforeJob} — record the start timestamp on the batch entity.</li>
 *   <li>{@code beforeStep} — no-op (hook available for future use).</li>
 *   <li>{@code afterStep} — log per-step read/write counts in real time.</li>
 *   <li>{@code afterJob} — compute final SUCCESS/FAILED counts, set {@link BatchStatus}, persist.</li>
 * </ul>
 *
 * <p>The listener is registered at both job level (via {@code JobBuilder.listener}) and step level
 * (via {@code StepBuilder.listener}) in {@code UrlBatchJobConfig}; Spring Batch calls the
 * appropriate interface methods at each lifecycle point.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrlBatchJobListener implements JobExecutionListener, StepExecutionListener {

    private final UrlFileBatchRepository batchRepository;
    private final UrlFileBatchRecordRepository recordRepository;

    // ── JobExecutionListener ─────────────────────────────────────────────────

    @Override
    public void beforeJob(JobExecution jobExecution) {
        String batchIdStr = jobExecution.getJobParameters().getString("batchId");
        if (batchIdStr == null) {
            log.warn("beforeJob — missing 'batchId' job parameter, cannot set startedAt");
            return;
        }

        UUID batchId = UUID.fromString(batchIdStr);
        batchRepository.findById(batchId).ifPresentOrElse(
                batch -> {
                    batch.setStartedAt(OffsetDateTime.now());
                    batchRepository.save(batch);
                    log.info("Batch {} started", batchId);
                },
                () -> log.error("beforeJob — UrlFileBatch {} not found in DB", batchId)
        );
    }

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

    // ── StepExecutionListener ────────────────────────────────────────────────

    /**
     * Called before each step execution begins. Reserved for future use.
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        log.debug("Step '{}' starting", stepExecution.getStepName());
    }

    /**
     * Called after each step execution completes. Logs cumulative read/write counts so that
     * progress is visible in application logs between chunk commits.
     */
    @Override
    public org.springframework.batch.core.ExitStatus afterStep(StepExecution stepExecution) {
        log.info("Step '{}' completed — read={}, write={}, skip={}",
                stepExecution.getStepName(),
                stepExecution.getReadCount(),
                stepExecution.getWriteCount(),
                stepExecution.getProcessSkipCount() + stepExecution.getWriteSkipCount());
        return stepExecution.getExitStatus();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private BatchStatus resolveFinalStatus(JobExecution jobExecution, long failedCount) {
        org.springframework.batch.core.BatchStatus springStatus = jobExecution.getStatus();
        if (springStatus == org.springframework.batch.core.BatchStatus.FAILED) {
            return BatchStatus.FAILED;
        }
        return failedCount > 0 ? BatchStatus.PARTIAL_SUCCESS : BatchStatus.COMPLETED;
    }
}
