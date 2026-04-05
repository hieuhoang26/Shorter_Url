package com.hhh.url.shorter_url.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Dedicated thread pool for batch URL processing jobs.
     * Keeps batch work off the common Spring async pool.
     */
    @Bean(name = "batchExecutor")
    public Executor batchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("batch-worker-");
        executor.initialize();
        return executor;
    }

    /**
     * Async JobLauncher backed by the batchExecutor thread pool.
     * Declared @Primary so it is injected wherever JobLauncher is requested,
     * overriding Spring Boot's auto-configured synchronous launcher.
     */
    @Bean
    @Primary
    public JobLauncher asyncJobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor((ThreadPoolTaskExecutor) batchExecutor());
        launcher.afterPropertiesSet();
        return launcher;
    }
}
