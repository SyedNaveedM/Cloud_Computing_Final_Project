package com.example.controller.service;

import com.example.controller.domain.Chunk;
import com.example.controller.domain.ChunkDeletionJob;
import com.example.controller.domain.ChunkWriteIntent;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.Replica;
import com.example.controller.domain.StoredObject;
import com.example.controller.repository.ChunkDeletionJobRepository;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ChunkWriteIntentRepository;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class UploadCleanupService {

    private final StoredObjectRepository objectRepository;
    private final ChunkRepository chunkRepository;
    private final ReplicaRepository replicaRepository;
    private final WorkerRepository workerRepository;
    private final ChunkWriteIntentRepository intentRepository;
    private final ChunkDeletionJobRepository deletionJobRepository;

    public UploadCleanupService(
            StoredObjectRepository objectRepository,
            ChunkRepository chunkRepository,
            ReplicaRepository replicaRepository,
            WorkerRepository workerRepository,
            ChunkWriteIntentRepository intentRepository,
            ChunkDeletionJobRepository deletionJobRepository) {

        this.objectRepository = objectRepository;
        this.chunkRepository = chunkRepository;
        this.replicaRepository = replicaRepository;
        this.workerRepository = workerRepository;
        this.intentRepository = intentRepository;
        this.deletionJobRepository = deletionJobRepository;
    }

    @Transactional
    public List<ChunkDeletion> removeIncompleteUpload(String objectId) {
        StoredObject object = objectRepository
                .findByIdForUpdate(objectId)
                .orElse(null);

        if (object == null
                || object.getStatus() != ObjectStatus.UPLOADING) {
            return List.of();
        }

        Instant now = Instant.now();

        List<ChunkWriteIntent> intents =
                intentRepository.findByObjectId(objectId);

        // Include possible copies, even when their PUT responses were lost.
        for (ChunkWriteIntent intent : intents) {
            queueDeletion(
                    objectId,
                    intent.getChunkId(),
                    intent.getWorkerId(),
                    intent.getWorkerUrl(),
                    now);
        }

        List<ChunkDeletion> deletions = new ArrayList<>();

        // Also include recorded replicas, including older uploads
        // that might not have write intents.
        for (Chunk chunk :
                chunkRepository.findByObjectIdOrderByChunkIndex(objectId)) {

            for (Replica replica :
                    replicaRepository.findByChunkId(chunk.getChunkId())) {

                String workerUrl = workerRepository
                        .findById(replica.getWorkerId())
                        .map(worker -> worker.getUrl())
                        .orElse(null);

                ChunkDeletionJob job = queueDeletion(
                        objectId,
                        chunk.getChunkId(),
                        replica.getWorkerId(),
                        workerUrl,
                        now);

                // Preserve the existing immediate deletion attempts
                // for recorded replicas. Their durable jobs remain pending.
                if (job.getWorkerUrl() != null
                        && !job.getWorkerUrl().isBlank()) {

                    deletions.add(new ChunkDeletion(
                            job.getWorkerId(),
                            job.getWorkerUrl(),
                            job.getChunkId()));
                }
            }

            replicaRepository.deleteByChunkId(chunk.getChunkId());
        }

        chunkRepository.deleteByObjectId(objectId);

        // Jobs now carry the cleanup information from these intents.
        // These deletions commit in the same transaction as the jobs.
        intentRepository.deleteAll(intents);
        objectRepository.delete(object);

        return List.copyOf(deletions);
    }

    private ChunkDeletionJob queueDeletion(
            String objectId,
            String chunkId,
            String workerId,
            String workerUrl,
            Instant now) {

        ChunkDeletionJob existing = deletionJobRepository
                .findByChunkIdAndWorkerId(chunkId, workerId)
                .orElse(null);

        if (existing != null) {
            if (existing.getObjectId() != null
                    && !Objects.equals(
                            existing.getObjectId(), objectId)) {

                throw new IllegalStateException(
                        "Deletion job belongs to a different object");
            }

            if (existing.getObjectId() == null) {
                existing.setObjectId(objectId);
            }

            if ((existing.getWorkerUrl() == null
                    || existing.getWorkerUrl().isBlank())
                    && workerUrl != null
                    && !workerUrl.isBlank()) {

                existing.setWorkerUrl(workerUrl);
            }

            // Preserve any existing retry history and schedule.
            return deletionJobRepository.save(existing);
        }

        ChunkDeletionJob job = new ChunkDeletionJob();
        job.setObjectId(objectId);
        job.setChunkId(chunkId);
        job.setWorkerId(workerId);
        job.setWorkerUrl(workerUrl);
        job.setCreatedAt(now);
        job.setNextAttemptAt(now);
        job.setAttemptCount(0);

        return deletionJobRepository.save(job);
    }

    public record ChunkDeletion(
            String workerId,
            String url,
            String chunkId) {
    }
}