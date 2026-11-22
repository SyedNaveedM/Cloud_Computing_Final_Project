package com.example.controller.service;

import com.example.controller.domain.ChunkDeletionJob;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.repository.ChunkDeletionJobRepository;
import com.example.controller.repository.WorkerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class ChunkDeletionJobService {

    private final ChunkDeletionJobRepository jobRepository;
    private final WorkerRepository workerRepository;

    public ChunkDeletionJobService(
            ChunkDeletionJobRepository jobRepository,
            WorkerRepository workerRepository) {

        this.jobRepository = jobRepository;
        this.workerRepository = workerRepository;
    }

    @Transactional(
            readOnly = true,
            propagation = Propagation.REQUIRES_NEW
    )
    public List<PendingDeletion> findDueJobs(Instant now) {
        Objects.requireNonNull(now, "Current time is required");

        return jobRepository
                .findTop100ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                        now)
                .stream()
                .map(PendingDeletion::from)
                .toList();
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<PendingDeletion> findDueJobsForObject(String objectId, Instant now) {
        Objects.requireNonNull(objectId, "Object ID is required");
        Objects.requireNonNull(now, "Current time is required");
        return jobRepository
                .findTop100ByObjectIdAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(objectId, now)
                .stream()
                .map(PendingDeletion::from)
                .toList();
    }

    /**
     * Call only after deleteChunkConfirmed returns successfully.
     * registrationToken must be captured before the worker request.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acknowledgeDeletion(
            PendingDeletion pending,
            String registrationToken) {

        Objects.requireNonNull(pending, "Pending deletion is required");

        if (registrationToken == null
                || registrationToken.isBlank()
                || pending.workerUrl() == null
                || pending.workerUrl().isBlank()) {

            return false;
        }

        // Lock the worker before the job.
        Worker currentWorker = workerRepository
                .findByIdForUpdate(pending.workerId())
                .orElse(null);

        if (currentWorker == null
                || currentWorker.getStatus() != WorkerStatus.ALIVE
                || !Objects.equals(
                        currentWorker.getUrl(),
                        pending.workerUrl())
                || !Objects.equals(
                        currentWorker.getRegistrationToken(),
                        registrationToken)) {

            return false;
        }

        ChunkDeletionJob job = lockMatchingJob(pending);

        if (job == null) {
            return false;
        }

        jobRepository.delete(job);
        jobRepository.flush();

        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(
            PendingDeletion pending,
            Instant now,
            String errorMessage) {

        Objects.requireNonNull(pending, "Pending deletion is required");
        Objects.requireNonNull(now, "Current time is required");

        ChunkDeletionJob job = lockMatchingJob(pending);

        if (job == null) {
            return false;
        }

        int previousAttempts = Math.max(0, job.getAttemptCount());

        long delaySeconds = retryDelaySeconds(previousAttempts);

        int updatedAttempts = previousAttempts == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : previousAttempts + 1;

        job.setAttemptCount(updatedAttempts);
        job.setNextAttemptAt(now.plusSeconds(delaySeconds));
        job.setLastError(boundedErrorMessage(errorMessage));

        jobRepository.saveAndFlush(job);

        return true;
    }

    private ChunkDeletionJob lockMatchingJob(PendingDeletion pending) {
        ChunkDeletionJob job = jobRepository
                .findByIdForUpdate(pending.jobId())
                .orElse(null);

        if (job == null
                || !PendingDeletion.from(job).equals(pending)) {

            return null;
        }

        return job;
    }

    private static long retryDelaySeconds(int previousAttempts) {
        long delaySeconds = 5;

        for (int i = 0; i < Math.min(previousAttempts, 6); i++) {
            delaySeconds = Math.min(delaySeconds * 2, 300);
        }

        return delaySeconds;
    }

    private static String boundedErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Worker deletion could not be confirmed";
        }

        return message.length() <= 1000
                ? message
                : message.substring(0, 1000);
    }

    public record PendingDeletion(
            long jobId,
            long version,
            String objectId,
            String chunkId,
            String workerId,
            String workerUrl,
            int attemptCount,
            Instant nextAttemptAt) {

        private static PendingDeletion from(ChunkDeletionJob job) {
            return new PendingDeletion(
                    job.getId(),
                    job.getVersion(),
                    job.getObjectId(),
                    job.getChunkId(),
                    job.getWorkerId(),
                    job.getWorkerUrl(),
                    job.getAttemptCount(),
                    job.getNextAttemptAt());
        }
    }
}
