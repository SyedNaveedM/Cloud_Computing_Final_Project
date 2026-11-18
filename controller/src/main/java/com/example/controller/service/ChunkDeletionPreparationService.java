package com.example.controller.service;

import com.example.controller.domain.ChunkDeletionJob;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.StoredObject;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.repository.ChunkDeletionJobRepository;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ChunkWriteIntentRepository;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import com.example.controller.service.ChunkDeletionJobService.PendingDeletion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class ChunkDeletionPreparationService {

    private final ChunkDeletionJobRepository jobRepository;
    private final StoredObjectRepository objectRepository;
    private final ChunkRepository chunkRepository;
    private final ReplicaRepository replicaRepository;
    private final ChunkWriteIntentRepository intentRepository;
    private final WorkerRepository workerRepository;

    public ChunkDeletionPreparationService(
            ChunkDeletionJobRepository jobRepository,
            StoredObjectRepository objectRepository,
            ChunkRepository chunkRepository,
            ReplicaRepository replicaRepository,
            ChunkWriteIntentRepository intentRepository,
            WorkerRepository workerRepository) {

        this.jobRepository = jobRepository;
        this.objectRepository = objectRepository;
        this.chunkRepository = chunkRepository;
        this.replicaRepository = replicaRepository;
        this.intentRepository = intentRepository;
        this.workerRepository = workerRepository;
    }

    @Transactional(
            readOnly = true,
            propagation = Propagation.REQUIRES_NEW
    )
    public Preparation prepare(
            PendingDeletion pending,
            Instant now) {

        Objects.requireNonNull(pending, "Pending deletion is required");
        Objects.requireNonNull(now, "Current time is required");

        ChunkDeletionJob job = jobRepository
                .findById(pending.jobId())
                .orElse(null);

        if (job == null || !matches(job, pending)) {
            return skip("Job was removed or changed");
        }

        if (pending.nextAttemptAt().isAfter(now)) {
            return skip("Job is not due yet");
        }

        // Process removed uploads and objects already marked DELETING.
        // An unknown chunk needs separate classification.
        if (pending.objectId() == null
                || pending.objectId().isBlank()) {

            return defer("Job has no object ID");
        }

        StoredObject object = objectRepository.findById(pending.objectId()).orElse(null);
        boolean deleting = object != null && object.getStatus() == ObjectStatus.DELETING;
        if (object != null && !deleting) return defer("Object is not being deleted");

        Chunk chunk = chunkRepository.findById(pending.chunkId()).orElse(null);
        if (chunk != null && (!deleting || !Objects.equals(chunk.getObjectId(), pending.objectId()))) {
            return defer("Chunk metadata is still live or belongs to a different object");
        }
        var replicas = replicaRepository.findByChunkId(pending.chunkId());
        if (!replicas.isEmpty() && (!deleting || chunk == null)) {
            return defer("Replica references cannot be attributed to this deletion");
        }
        var intents = intentRepository.findByChunkId(pending.chunkId());
        if ((!deleting && !intents.isEmpty()) || intents.stream()
                .anyMatch(i -> !Objects.equals(i.getObjectId(), pending.objectId()))) {
            return defer("Write intents still reference another or live object");
        }

        Worker worker = workerRepository
                .findById(pending.workerId())
                .orElse(null);

        if (worker == null) {
            return defer("Worker is not registered");
        }

        if (worker.getStatus() != WorkerStatus.ALIVE) {
            return defer("Worker is not ALIVE");
        }

        if (pending.workerUrl() == null
                || pending.workerUrl().isBlank()
                || !Objects.equals(
                        pending.workerUrl(), worker.getUrl())) {

            return defer("Worker URL is missing or has changed");
        }

        String token = worker.getRegistrationToken();

        if (token == null || token.isBlank()) {
            return defer("Worker has no registration token");
        }

        return new Preparation(Disposition.READY, token, null);
    }

    private static boolean matches(
            ChunkDeletionJob job,
            PendingDeletion pending) {

        PendingDeletion current = new PendingDeletion(
                job.getId(),
                job.getVersion(),
                job.getObjectId(),
                job.getChunkId(),
                job.getWorkerId(),
                job.getWorkerUrl(),
                job.getAttemptCount(),
                job.getNextAttemptAt());

        return current.equals(pending);
    }

    private static Preparation skip(String reason) {
        return new Preparation(Disposition.SKIP, null, reason);
    }

    private static Preparation defer(String reason) {
        return new Preparation(Disposition.DEFER, null, reason);
    }

    public enum Disposition {
        READY,
        SKIP,
        DEFER
    }

    public record Preparation(
            Disposition disposition,
            String registrationToken,
            String reason) {
    }
}
