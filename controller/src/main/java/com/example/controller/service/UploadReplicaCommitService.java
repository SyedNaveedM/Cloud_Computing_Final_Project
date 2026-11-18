package com.example.controller.service;

import com.example.common.Constants;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.Replica;
import com.example.controller.domain.ReplicaState;
import com.example.controller.domain.StoredObject;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class UploadReplicaCommitService {

    private final WorkerRepository workerRepository;
    private final StoredObjectRepository objectRepository;
    private final ChunkRepository chunkRepository;
    private final ReplicaRepository replicaRepository;

    public UploadReplicaCommitService(
            WorkerRepository workerRepository,
            StoredObjectRepository objectRepository,
            ChunkRepository chunkRepository,
            ReplicaRepository replicaRepository) {

        this.workerRepository = workerRepository;
        this.objectRepository = objectRepository;
        this.chunkRepository = chunkRepository;
        this.replicaRepository = replicaRepository;
    }

    @Transactional
    public boolean tryCommit(
            Chunk chunk,
            List<UploadTarget> successfulTargets) {

        Objects.requireNonNull(chunk, "Chunk is required");

        List<UploadTarget> targets = List.copyOf(successfulTargets);

        long distinctWorkers = targets.stream()
                .map(UploadTarget::workerId)
                .distinct()
                .count();

        if (targets.size() != Constants.REPLICATION_FACTOR
                || distinctWorkers != Constants.REPLICATION_FACTOR) {
            throw new IllegalArgumentException(
                    "A chunk requires successful writes to "
                    + Constants.REPLICATION_FACTOR
                    + " distinct workers");
        }

        // Always acquire multiple worker locks in the same order.
        List<UploadTarget> orderedTargets = targets.stream()
                .sorted(Comparator.comparing(UploadTarget::workerId))
                .toList();

        for (UploadTarget target : orderedTargets) {
            Worker current = workerRepository
                    .findByIdForUpdate(target.workerId())
                    .orElse(null);

            if (current == null
                    || current.getStatus() != WorkerStatus.ALIVE
                    || target.registrationToken() == null
                    || target.registrationToken().isBlank()
                    || !Objects.equals(
                            target.registrationToken(),
                            current.getRegistrationToken())
                    || !Objects.equals(
                            target.url(), current.getUrl())) {

                return false;
            }
        }

        // Match registration completion's order: workers, then objects.
        StoredObject object = objectRepository
                .findByIdForUpdate(chunk.getObjectId())
                .orElse(null);

        if (object == null
                || object.getStatus() != ObjectStatus.UPLOADING) {
            return false;
        }

        // This method publishes a newly generated chunk ID.
        if (chunkRepository.existsById(chunk.getChunkId())) {
            throw new IllegalStateException(
                    "Chunk metadata already exists: "
                    + chunk.getChunkId());
        }

        // Publish the chunk and all replica records together.
        chunkRepository.save(chunk);

        for (UploadTarget target : orderedTargets) {
            replicaRepository.save(new Replica(
                    chunk.getChunkId(),
                    target.workerId(),
                    ReplicaState.OK));
        }

        return true;
    }

    public record UploadTarget(
            String workerId,
            String url,
            String registrationToken) {

        public UploadTarget {
            Objects.requireNonNull(workerId, "Worker ID is required");
            Objects.requireNonNull(url, "Worker URL is required");
        }

        // Capture this before sending the chunk to the worker.
        public static UploadTarget snapshotOf(Worker worker) {
            return new UploadTarget(
                    worker.getWorkerId(),
                    worker.getUrl(),
                    worker.getRegistrationToken());
        }
    }
}