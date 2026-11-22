package com.example.controller.service;

import com.example.controller.client.WorkerClient;
import com.example.controller.client.WorkerUnavailableException;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.service.ChunkDeletionJobService.PendingDeletion;
import com.example.controller.service.ChunkDeletionPreparationService.Disposition;
import com.example.controller.service.ChunkDeletionPreparationService.Preparation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class ChunkDeletionProcessor {

    private final ChunkDeletionPreparationService preparationService;
    private final ChunkDeletionJobService jobService;
    private final WorkerClient workerClient;

    public ChunkDeletionProcessor(
            ChunkDeletionPreparationService preparationService,
            ChunkDeletionJobService jobService,
            WorkerClient workerClient) {

        this.preparationService = preparationService;
        this.jobService = jobService;
        this.workerClient = workerClient;
    }

    @Transactional(propagation = Propagation.NEVER)
    public Result process(PendingDeletion pending) {

        Objects.requireNonNull(pending, "Pending deletion is required");

        Preparation preparation = preparationService.prepare(
                pending, Instant.now());

        if (preparation.disposition() == Disposition.SKIP) {
            return Result.SKIPPED;
        }

        if (preparation.disposition() == Disposition.DEFER) {
            return retryOrSkip(pending, preparation.reason());
        }

        // Build a request target from the values checked before DELETE.
        // This object is not saved to the database.
        Worker target = new Worker();
        target.setWorkerId(pending.workerId());
        target.setUrl(pending.workerUrl());
        target.setStatus(WorkerStatus.ALIVE);
        target.setRegistrationToken(preparation.registrationToken());

        try {
            // The preparation transaction has already finished.
            workerClient.deleteChunkConfirmed(
                    target, pending.chunkId());

        } catch (WorkerUnavailableException | IllegalArgumentException error) {

            return retryOrSkip(
                    pending,
                    error.getClass().getSimpleName()
                            + ": " + error.getMessage());
        }

        boolean acknowledged = jobService.acknowledgeDeletion(
                pending,
                preparation.registrationToken());

        if (acknowledged) {
            return Result.COMPLETED;
        }

        return retryOrSkip(
                pending,
                "Worker registration or deletion job changed "
                        + "while deletion was running");
    }

    private Result retryOrSkip(
            PendingDeletion pending,
            String reason) {

        boolean recorded = jobService.recordFailure(
                pending,
                Instant.now(),
                reason);

        return recorded
                ? Result.RETRY_SCHEDULED
                : Result.SKIPPED;
    }

    public enum Result {
        COMPLETED,
        RETRY_SCHEDULED,
        SKIPPED
    }
}