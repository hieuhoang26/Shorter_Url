package com.hhh.url.shorter_url.batch.config;

import com.hhh.url.shorter_url.batch.dto.ProcessedUrlRow;
import com.hhh.url.shorter_url.batch.dto.UrlRowDTO;
import com.hhh.url.shorter_url.batch.listener.UrlBatchJobListener;
import com.hhh.url.shorter_url.batch.processor.UrlBatchItemProcessor;
import com.hhh.url.shorter_url.batch.reader.UrlExcelItemReader;
import com.hhh.url.shorter_url.batch.writer.UrlBatchItemWriter;
import com.hhh.url.shorter_url.repository.UrlFileBatchRecordRepository;
import com.hhh.url.shorter_url.repository.UrlFileBatchRepository;
import com.hhh.url.shorter_url.repository.UrlRepository;
import com.hhh.url.shorter_url.service.Base62Service;
import com.hhh.url.shorter_url.service.ObjectStorageService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch Job definition for bulk URL import.
 *
 * <p>Pipeline: {@link UrlExcelItemReader} → {@link UrlBatchItemProcessor} → {@link UrlBatchItemWriter}
 * with configurable chunk size. Job status is finalised by {@link UrlBatchJobListener}.
 *
 * <p>The step is configured with {@code faultTolerant().skip(Exception.class)} so that individual
 * bad rows are skipped (up to {@code app.batch.skip-limit}) rather than aborting the entire job.
 */
@Configuration
public class UrlBatchJobConfig {

    // Reader

    /**
     * Step-scoped reader; {@code objectStoragePath} is resolved from job parameters at runtime.
     */
    @Bean
    @StepScope
    public UrlExcelItemReader urlExcelItemReader(
            ObjectStorageService objectStorageService,
            @Value("#{jobParameters['objectStoragePath']}") String objectStoragePath) {
        return new UrlExcelItemReader(objectStorageService, objectStoragePath);
    }

    // Processor

    /**
     * Step-scoped processor; {@code batchId} is resolved from job parameters at runtime.
     */
    @Bean
    @StepScope
    public UrlBatchItemProcessor urlBatchItemProcessor(
            UrlFileBatchRepository batchRepository,
            @Value("#{jobParameters['batchId']}") String batchId) {
        return new UrlBatchItemProcessor(batchRepository, batchId);
    }

    // Writer

    @Bean
    @StepScope
    public UrlBatchItemWriter urlBatchItemWriter(
            UrlRepository urlRepository,
            UrlFileBatchRecordRepository recordRepository,
            Base62Service base62Service) {
        return new UrlBatchItemWriter(urlRepository, recordRepository, base62Service);
    }

    // Step

    @Bean
    public Step urlImportStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            UrlExcelItemReader urlExcelItemReader,
            UrlBatchItemProcessor urlBatchItemProcessor,
            UrlBatchItemWriter urlBatchItemWriter,
            UrlBatchJobListener listener,
            @Value("${app.batch.chunk-size:100}") int chunkSize,
            @Value("${app.batch.skip-limit:1000}") int skipLimit) {
        return new StepBuilder("urlImportStep", jobRepository)
                .<UrlRowDTO, ProcessedUrlRow>chunk(chunkSize, transactionManager)
                .reader(urlExcelItemReader)
                .processor(urlBatchItemProcessor)
                .writer(urlBatchItemWriter)
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(skipLimit)
                .listener(listener)
                .build();
    }

    // Job

    @Bean
    public Job urlImportJob(
            JobRepository jobRepository,
            Step urlImportStep,
            UrlBatchJobListener listener) {
        return new JobBuilder("urlImportJob", jobRepository)
                .start(urlImportStep)
                .listener(listener)
                .build();
    }
}
