package com.example.controller.config;

import com.example.controller.domain.ObjectStatus;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Bean;
import java.time.Instant;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "objectstore.maintenance.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
@Order(-10)
public class BackgroundMaintenance implements ApplicationRunner {
    private final WorkerHeartbeatService heartbeats;
    private final ReplicationManager replication;
    private final ChunkDeletionJobService jobs;
    private final ChunkDeletionProcessor processor;
    private final ObjectDeletionMetadataService deletion;
    private final StoredObjectRepository objects;
    private volatile boolean ready;

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Failure detection must not wait behind slow worker HTTP calls.
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("maintenance-");
        return scheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        heartbeats.requireRegistrationAfterControllerRestart();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() { ready = true; }

    @Scheduled(fixedDelayString = "${objectstore.failure-check-ms:2000}")
    public void detectFailures() {
        if (!ready) return;
        int count = heartbeats.expireBefore(Instant.now().minusSeconds(20));
        if (count > 0) log.warn("Marked {} workers unavailable after heartbeat timeout", count);
    }

    @Scheduled(fixedDelayString = "${objectstore.repair-delay-ms:10000}")
    public void repairAndScrub() {
        if (ready) replication.runOnce();
    }

    @Scheduled(fixedDelayString = "${objectstore.cleanup-delay-ms:3000}")
    public void cleanup() {
        if (!ready) return;
        for (var pending : jobs.findDueJobs(Instant.now())) {
            try { processor.process(pending); }
            catch (RuntimeException error) {
                log.warn("Deletion job {} remains pending: {}", pending.jobId(), error.getMessage());
            }
        }
        for (var object : objects.findByStatus(ObjectStatus.DELETING)) {
            try { deletion.finish(object.getId()); }
            catch (RuntimeException error) {
                log.warn("Object deletion {} will retry: {}", object.getId(), error.getMessage());
            }
        }
    }
}
