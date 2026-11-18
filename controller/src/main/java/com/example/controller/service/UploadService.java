package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.common.Constants;
import com.example.controller.client.ChunkRejectedException;
import com.example.controller.client.WorkerClient;
import com.example.controller.client.WorkerUnavailableException;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.StoredObject;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.InsufficientWorkersException;
import com.example.controller.exception.InvalidRequestException;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import com.example.controller.service.UploadReplicaCommitService.UploadTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class UploadService {

    private final StoredObjectRepository objectRepository;
    private final WorkerRepository workerRepository;
    private final PlacementService placementService;
    private final WorkerClient workerClient;
    private final ObjectMapper objectMapper;
    private final UploadReplicaCommitService replicaCommitService;
    private final UploadCleanupService cleanupService;
    private final ChunkWriteIntentService writeIntentService;
    private final UploadCompletionService completionService;
  public UploadService(
        StoredObjectRepository objectRepository,
        WorkerRepository workerRepository,
        PlacementService placementService,
        WorkerClient workerClient,
        ObjectMapper objectMapper,
        UploadReplicaCommitService replicaCommitService,
        UploadCleanupService cleanupService,
        ChunkWriteIntentService writeIntentService,
        UploadCompletionService completionService) {

    this.objectRepository = objectRepository;
    this.workerRepository = workerRepository;
    this.placementService = placementService;
    this.workerClient = workerClient;
    this.objectMapper = objectMapper;
    this.replicaCommitService = replicaCommitService;
    this.cleanupService = cleanupService;
    this.writeIntentService = writeIntentService;
    this.completionService = completionService;
}

    @Transactional(propagation = Propagation.NEVER)
    public StoredObject upload(
            InputStream fileStream,
            String name,
            String contentType,
            String metadataJson) {

        Map<String, String> customMetadata = parseMetadata(metadataJson);

        if (workerRepository.findByStatus(WorkerStatus.ALIVE).size()
                < Constants.REPLICATION_FACTOR) {
            throw new InsufficientWorkersException(
                    "Need at least " + Constants.REPLICATION_FACTOR
                    + " ALIVE workers to accept an upload");
        }

        String objectId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        StoredObject object = new StoredObject();
        object.setId(objectId);
        object.setName(name);
        object.setContentType(contentType);
        object.setStatus(ObjectStatus.UPLOADING);
        object.setCreatedAt(now);
        object.setModifiedAt(now);
        object.setCustomMetadata(customMetadata);
        objectRepository.save(object);

        try {
            ChunkingResult result = streamChunks(fileStream, objectId);

            return completionService.complete(
                    objectId,
                    result.totalSize(),
                    result.chunkCount(),
                    result.wholeObjectChecksum());
        } catch (IOException error) {
            UncheckedIOException failure = new UncheckedIOException(
                    "Failed reading upload stream", error);

            cleanupAfterFailure(objectId, failure);
            throw failure;
        } catch (RuntimeException failure) {
            cleanupAfterFailure(objectId, failure);
            throw failure;
        }
    }

    private ChunkingResult streamChunks(
            InputStream fileStream,
            String objectId) throws IOException {

        MessageDigest wholeObjectDigest;
        try {
            wholeObjectDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "SHA-256 algorithm not available", error);
        }

        long totalSize = 0;
        int chunkIndex = 0;
        byte[] buffer;

        while ((buffer = fileStream.readNBytes(Constants.CHUNK_SIZE))
                .length > 0) {

            wholeObjectDigest.update(buffer);
            writeChunkWithReplicas(objectId, chunkIndex, buffer);
            totalSize += buffer.length;
            chunkIndex++;
        }

        return new ChunkingResult(
                totalSize,
                chunkIndex,
                HexFormat.of().formatHex(wholeObjectDigest.digest()));
    }

    private record ChunkingResult(
            long totalSize,
            int chunkCount,
            String wholeObjectChecksum) {
    }

    private void writeChunkWithReplicas(
            String objectId,
            int chunkIndex,
            byte[] data) {

        String chunkId = UUID.randomUUID().toString();
        String checksum = ChecksumUtil.sha256(data);

        Set<String> attemptedWorkerIds = new HashSet<>();
        List<UploadTarget> successfulTargets = new ArrayList<>();

        List<PlacementService.Reservation> reservations = new ArrayList<>();
        try {
            var initial = placementService.reserve(chunkId, Constants.REPLICATION_FACTOR, Set.of());
            reservations.add(initial);
            Deque<Worker> toTry = new ArrayDeque<>(initial.workers());

            while (successfulTargets.size() < Constants.REPLICATION_FACTOR) {
                if (toTry.isEmpty()) {
                    var replacement = placementService.reserve(chunkId, 1, attemptedWorkerIds);
                    reservations.add(replacement);
                    toTry.addAll(replacement.workers());
                }
                Worker worker = toTry.removeFirst();
                attemptedWorkerIds.add(worker.getWorkerId());

                // Remember the selected registration before the network call.
                UploadTarget target = UploadTarget.snapshotOf(worker);
                if (!writeIntentService.tryRecord(objectId, chunkId, target)) {
                    throw new InsufficientWorkersException(
                            "Worker registration or upload state changed "
                                    + "before sending the chunk; retry the upload");
                }
                try {
                    workerClient.putChunk(worker, chunkId, data, checksum);
                    successfulTargets.add(target);
                } catch (WorkerUnavailableException | ChunkRejectedException error) {
                    log.warn("Failed to PUT chunk {} to worker {}, retrying with a replacement: {}",
                            chunkId, worker.getWorkerId(), error.getMessage());
                }
            }

            Chunk chunk = new Chunk(chunkId, objectId, chunkIndex, data.length, checksum);
            if (!replicaCommitService.tryCommit(chunk, successfulTargets)) {
                throw new InsufficientWorkersException(
                        "Worker registration or upload state changed "
                                + "before replica records could be committed; retry the upload");
            }
        } finally {
            reservations.forEach(PlacementService.Reservation::close);
        }
    }

    /**
     * Removes incomplete upload metadata, then attempts to delete
     * the recorded worker copies outside the database transaction.
     */
    @Transactional(propagation = Propagation.NEVER)
    public void rollbackUpload(String objectId) {
        List<UploadCleanupService.ChunkDeletion> deletions =
                cleanupService.removeIncompleteUpload(objectId);

        for (UploadCleanupService.ChunkDeletion deletion : deletions) {
            Worker target = new Worker();
            target.setWorkerId(deletion.workerId());
            target.setUrl(deletion.url());

            try {
                workerClient.deleteChunk(target, deletion.chunkId());
            } catch (RuntimeException error) {
                log.warn(
                        "Could not delete abandoned chunk {} from worker {}: {}",
                        deletion.chunkId(),
                        deletion.workerId(),
                        error.getMessage());
            }
        }
    }

    private void cleanupAfterFailure(
            String objectId,
            RuntimeException failure) {

        try {
            rollbackUpload(objectId);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            log.error(
                    "Could not clean up failed upload {}",
                    objectId,
                    cleanupFailure);
        }
    }

    private Map<String, String> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new HashMap<>();
        }

        try {
            Map<String, String> parsed = objectMapper.readValue(
                    metadataJson,
                    new TypeReference<Map<String, String>>() {});

            return parsed == null
                    ? new HashMap<>()
                    : new HashMap<>(parsed);
        } catch (JacksonException error) {
            throw new InvalidRequestException(
                    "metadata must be a JSON object of string key-value pairs");
        }
    }
}
