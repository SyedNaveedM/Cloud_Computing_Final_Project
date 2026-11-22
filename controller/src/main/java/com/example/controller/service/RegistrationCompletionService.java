package com.example.controller.service;

import com.example.common.dto.ChunkInfo;
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
import com.example.controller.service.RegistrationChunkVerifier.Result;
import com.example.controller.service.RegistrationMetadataService.RegistrationAttempt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Service
public class RegistrationCompletionService {

    private final WorkerRepository workerRepository;
    private final ChunkRepository chunkRepository;
    private final ReplicaRepository replicaRepository;
    private final StoredObjectRepository objectRepository;
    private final RegistrationInventoryValidator inventoryValidator;

    public RegistrationCompletionService(
            WorkerRepository workerRepository,
            ChunkRepository chunkRepository,
            ReplicaRepository replicaRepository,
            StoredObjectRepository objectRepository,
            RegistrationInventoryValidator inventoryValidator) {
        this.workerRepository = workerRepository;
        this.chunkRepository = chunkRepository;
        this.replicaRepository = replicaRepository;
        this.objectRepository = objectRepository;
        this.inventoryValidator = inventoryValidator;
    }

    @Transactional
    public CompletionResult complete(
            RegistrationAttempt attempt,
            List<ChunkInfo> confirmedInventory,
            Map<String, ChunkCheck> checkedChunks) {

        Objects.requireNonNull(attempt, "Registration attempt is required");

        Map<String, ChunkInfo> inventory =
                inventoryValidator.validate(confirmedInventory);

        Map<String, ChunkCheck> checks = Map.copyOf(checkedChunks);

        Worker worker = workerRepository
                .findByIdForUpdate(attempt.workerId())
                .orElse(null);

        if (worker == null
                || worker.getStatus() != WorkerStatus.REGISTERING
                || attempt.token() == null
                || !Objects.equals(
                        attempt.token(), worker.getRegistrationToken())
                || !Objects.equals(attempt.url(), worker.getUrl())) {
            return CompletionResult.STALE_ATTEMPT;
        }

        for (ChunkCheck check : checks.values()) {
            if (check.result() == null
                    || check.result() == Result.UNAVAILABLE) {
                return CompletionResult.RETRY_REQUIRED;
            }
        }

        Set<String> chunkIds = new TreeSet<>(inventory.keySet());
        chunkIds.addAll(checks.keySet());

        for (Replica replica :
                replicaRepository.findByWorkerId(attempt.workerId())) {
            chunkIds.add(replica.getChunkId());
        }

        Map<String, Chunk> chunks = new HashMap<>();

        for (Chunk chunk : chunkRepository.findAllById(chunkIds)) {
            chunks.put(chunk.getChunkId(), chunk);
        }

        Map<String, StoredObject> objects = new HashMap<>();

        // Use a consistent order when locking several objects.
        List<String> objectIds = chunks.values().stream()
                .map(Chunk::getObjectId)
                .distinct()
                .sorted()
                .toList();

        for (String objectId : objectIds) {
            objectRepository.findByIdForUpdate(objectId)
                    .ifPresent(object -> objects.put(objectId, object));
        }

        Map<String, Result> decisions = new HashMap<>();

        // Validate the entire batch before changing any replica.
        for (String chunkId : chunkIds) {
            Chunk chunk = chunks.get(chunkId);

            if (chunk == null
                    || !objects.containsKey(chunk.getObjectId())) {
                // Remove an obsolete location record if one exists.
                // This does not delete any bytes from the worker.
                decisions.put(chunkId, Result.MISSING);
                continue;
            }

            StoredObject object = objects.get(chunk.getObjectId());

            if (object.getStatus() == ObjectStatus.DELETING) {
                decisions.put(chunkId, Result.MISSING);
                continue;
            }
            if (object.getStatus() != ObjectStatus.COMPLETE) {
                return CompletionResult.RETRY_REQUIRED;
            }

            ChunkCheck check = checks.get(chunkId);

            if (check == null
                    || !Objects.equals(
                            check.expectedObjectId(), chunk.getObjectId())
                    || check.expectedSize() != chunk.getSize()
                    || check.expectedChecksum() == null
                    || !check.expectedChecksum()
                            .equalsIgnoreCase(chunk.getChecksum())) {
                return CompletionResult.RETRY_REQUIRED;
            }

            decisions.put(chunkId, check.result());
        }

        // Reload after taking the object locks: deletion may have
        // removed replica records while we were waiting.
        Map<String, Replica> existingReplicas = new HashMap<>();

        for (Replica replica :
                replicaRepository.findByWorkerId(attempt.workerId())) {
            existingReplicas.put(replica.getChunkId(), replica);
        }

        if (!chunkIds.containsAll(existingReplicas.keySet())) {
            return CompletionResult.RETRY_REQUIRED;
        }

        // All checks passed. Apply the changes in this transaction.
        for (String chunkId : chunkIds) {
            Result decision = decisions.get(chunkId);
            Replica replica = existingReplicas.get(chunkId);

            if (decision == Result.MISSING) {
                if (replica != null) {
                    replicaRepository.delete(replica);
                }
                continue;
            }

            ReplicaState state = decision == Result.VERIFIED
                    ? ReplicaState.OK
                    : ReplicaState.CORRUPT;

            if (replica == null) {
                replica = new Replica(
                        chunkId, attempt.workerId(), state);
            } else {
                replica.setState(state);
            }

            replicaRepository.save(replica);
        }

        // Inventory counters estimate payload load, not physical disk use.
        long inventoriedBytes = inventory.values().stream()
                .mapToLong(ChunkInfo::size)
                .sum();

        worker.setUsedBytes(inventoriedBytes);
        worker.setChunkCount(inventory.size());
        worker.setLastHeartbeat(Instant.now());
        worker.setStatus(WorkerStatus.ALIVE);
        workerRepository.save(worker);

        return CompletionResult.COMPLETED;
    }

    public record ChunkCheck(
            String expectedObjectId,
            long expectedSize,
            String expectedChecksum,
            Result result) {
    }

    public enum CompletionResult {
        COMPLETED,
        STALE_ATTEMPT,
        RETRY_REQUIRED
    }
}
