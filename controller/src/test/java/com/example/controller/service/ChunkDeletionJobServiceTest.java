package com.example.controller.service;

import com.example.controller.domain.ChunkDeletionJob;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.repository.ChunkDeletionJobRepository;
import com.example.controller.repository.WorkerRepository;
import com.example.controller.service.ChunkDeletionJobService.PendingDeletion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chunk-deletion-job-service-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChunkDeletionJobServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-11-13T00:00:00Z");

    private static final String WORKER_ID = "deletion-test-worker";
    private static final String WORKER_URL = "http://localhost:8082";

    private static final String TOKEN_A =
            "00000000-0000-0000-0000-000000000001";

    private static final String TOKEN_B =
            "00000000-0000-0000-0000-000000000002";

    @Autowired
    private ChunkDeletionJobService service;

    @Autowired
    private ChunkDeletionJobRepository jobRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @BeforeEach
    void prepareDatabase() {
        assertFalse(
                TransactionSynchronizationManager
                        .isActualTransactionActive(),
                "The test must let service transactions commit independently");

        jobRepository.deleteAll();
        workerRepository.deleteAll();

        Worker worker = new Worker(
                WORKER_ID,
                WORKER_URL,
                NOW,
                WorkerStatus.ALIVE,
                0L,
                0);

        worker.setRegistrationToken(TOKEN_A);
        workerRepository.saveAndFlush(worker);
    }

    @Test
    void failureIsSavedAndJobWaitsUntilItsRetryTime() {
        PendingDeletion pending = createPendingJob();

        assertTrue(service.recordFailure(
                pending,
                NOW,
                "Connection timed out"));

        // Read again after the service transaction has committed.
        ChunkDeletionJob stored = loadJob(pending);

        assertEquals(1, stored.getAttemptCount());
        assertEquals("Connection timed out", stored.getLastError());
        assertEquals(
                NOW.plusSeconds(5),
                stored.getNextAttemptAt());

        assertTrue(stored.getVersion() > pending.version());

        assertTrue(
                service.findDueJobs(NOW.plusSeconds(4)).isEmpty());

        var due = service.findDueJobs(NOW.plusSeconds(5));

        assertEquals(1, due.size());
        assertEquals(pending.jobId(), due.getFirst().jobId());
    }

    @Test
    void matchingAcknowledgmentRemovesJobAndLateResultsDoNotRecreateIt() {
        PendingDeletion pending = createPendingJob();

        // At this service boundary, worker confirmation is assumed.
        assertTrue(service.acknowledgeDeletion(pending, TOKEN_A));

        assertFalse(jobRepository.existsById(pending.jobId()));

        // Repeated or delayed results must not recreate a finished job.
        assertFalse(service.acknowledgeDeletion(pending, TOKEN_A));

        assertFalse(service.recordFailure(
                pending,
                NOW.plusSeconds(10),
                "Late failure"));

        assertFalse(jobRepository.existsById(pending.jobId()));
    }

    @Test
    void outdatedResultsCannotRemoveOrRescheduleAnUpdatedJob() {
        PendingDeletion oldSnapshot = createPendingJob();

        // Another result updates the database after this snapshot was read.
        assertTrue(service.recordFailure(
                oldSnapshot,
                NOW,
                "First failure"));

        ChunkDeletionJob afterFailure = loadJob(oldSnapshot);

        assertTrue(afterFailure.getVersion() > oldSnapshot.version());

        // Both results still refer to the old snapshot.
        assertFalse(service.acknowledgeDeletion(
                oldSnapshot,
                TOKEN_A));

        assertFalse(service.recordFailure(
                oldSnapshot,
                NOW.plusSeconds(100),
                "Outdated failure"));

        ChunkDeletionJob unchanged = loadJob(oldSnapshot);

        assertEquals(
                afterFailure.getVersion(),
                unchanged.getVersion());

        assertEquals(1, unchanged.getAttemptCount());
        assertEquals("First failure", unchanged.getLastError());

        assertEquals(
                afterFailure.getNextAttemptAt(),
                unchanged.getNextAttemptAt());

        // A fresh snapshot can still be acknowledged.
        PendingDeletion freshSnapshot = service
                .findDueJobs(unchanged.getNextAttemptAt())
                .getFirst();

        assertTrue(service.acknowledgeDeletion(
                freshSnapshot,
                TOKEN_A));

        assertFalse(jobRepository.existsById(oldSnapshot.jobId()));
    }

    @Test
    void changedRegistrationTokenPreventsOldAcknowledgment() {
        PendingDeletion pending = createPendingJob();

        Worker worker = workerRepository
                .findById(WORKER_ID)
                .orElseThrow();

        // Simulate a newer registration that is already ALIVE.
        // Keep the same URL and status so only the token changes.
        worker.setRegistrationToken(TOKEN_B);
        workerRepository.saveAndFlush(worker);

        assertFalse(service.acknowledgeDeletion(pending, TOKEN_A));

        ChunkDeletionJob remaining = loadJob(pending);

        assertEquals(
                pending.version(),
                remaining.getVersion().longValue());

        assertEquals(0, remaining.getAttemptCount());
        assertEquals(NOW, remaining.getNextAttemptAt());
    }

    @Test
    void repeatedFailuresBackOffToFiveMinutesAndKeepErrorBounded() {
        PendingDeletion pending = createPendingJob();
        Instant attemptTime = NOW;

        long[] expectedDelays = {
                5, 10, 20, 40, 80, 160, 300, 300
        };

        String longError = "x".repeat(1500);

        for (int index = 0; index < expectedDelays.length; index++) {
            assertTrue(service.recordFailure(
                    pending,
                    attemptTime,
                    longError));

            ChunkDeletionJob stored = loadJob(pending);

            Instant expectedRetry =
                    attemptTime.plusSeconds(expectedDelays[index]);

            assertEquals(index + 1, stored.getAttemptCount());
            assertEquals(expectedRetry, stored.getNextAttemptAt());
            assertEquals(1000, stored.getLastError().length());

            assertTrue(
                    service.findDueJobs(
                            expectedRetry.minusSeconds(1)).isEmpty());

            // Advance the supplied time without sleeping.
            pending = service.findDueJobs(expectedRetry).getFirst();
            attemptTime = expectedRetry;
        }
    }

    private PendingDeletion createPendingJob() {
        ChunkDeletionJob job = new ChunkDeletionJob();

        job.setObjectId(UUID.randomUUID().toString());
        job.setChunkId(UUID.randomUUID().toString());
        job.setWorkerId(WORKER_ID);
        job.setWorkerUrl(WORKER_URL);
        job.setCreatedAt(NOW);
        job.setNextAttemptAt(NOW);
        job.setAttemptCount(0);

        jobRepository.saveAndFlush(job);

        var pending = service.findDueJobs(NOW);

        assertEquals(1, pending.size());

        return pending.getFirst();
    }

    private ChunkDeletionJob loadJob(PendingDeletion pending) {
        return jobRepository
                .findById(pending.jobId())
                .orElseThrow();
    }
}