package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.common.Constants;
import com.example.controller.client.ChunkRejectedException;
import com.example.controller.client.WorkerClient;
import com.example.controller.client.WorkerUnavailableException;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.Replica;
import com.example.controller.domain.ReplicaState;
import com.example.controller.domain.StoredObject;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.InsufficientWorkersException;
import com.example.controller.exception.InvalidRequestException;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ChunkRepository chunkRepository;
    private final ReplicaRepository replicaRepository;
    private final WorkerRepository workerRepository;
    private final PlacementService placementService;
    private final WorkerClient workerClient;
    private final ObjectMapper objectMapper;

    public UploadService(StoredObjectRepository objectRepository,
                          ChunkRepository chunkRepository,
                          ReplicaRepository replicaRepository,
                          WorkerRepository workerRepository,
                          PlacementService placementService,
                          WorkerClient workerClient,
                          ObjectMapper objectMapper) {
        this.objectRepository = objectRepository;
        this.chunkRepository = chunkRepository;
        this.replicaRepository = replicaRepository;
        this.workerRepository = workerRepository;
        this.placementService = placementService;
        this.workerClient = workerClient;
        this.objectMapper = objectMapper;
    }

    public StoredObject upload(InputStream fileStream, String name, String contentType, String metadataJson) {
        Map<String, String> customMetadata = parseMetadata(metadataJson);

        if (workerRepository.findByStatus(WorkerStatus.ALIVE).size() < Constants.REPLICATION_FACTOR) {
            throw new InsufficientWorkersException(
                    "Need at least " + Constants.REPLICATION_FACTOR + " ALIVE workers to accept an upload");
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

            object.setSize(result.totalSize());
            object.setChunkCount(result.chunkCount());
            object.setChecksum(result.wholeObjectChecksum());
            object.setStatus(ObjectStatus.COMPLETE);
            object.setModifiedAt(Instant.now());
            return objectRepository.save(object);
        } catch (IOException e) {
            rollbackUpload(objectId);
            throw new UncheckedIOException("Failed reading upload stream", e);
        } catch (InsufficientWorkersException e) {
            rollbackUpload(objectId);
            throw e;
        }
    }

    /**
     * Streams the input in CHUNK_SIZE pieces, replicating each chunk before reading the next
     * (so the whole file never has to sit in memory) while updating a running whole-object
     * digest alongside each chunk's own checksum.
     */
    private ChunkingResult streamChunks(InputStream fileStream, String objectId) throws IOException {
        MessageDigest wholeObjectDigest;
        try {
            wholeObjectDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }

        long totalSize = 0;
        int chunkIndex = 0;
        byte[] buffer;
        while ((buffer = fileStream.readNBytes(Constants.CHUNK_SIZE)).length > 0) {
            wholeObjectDigest.update(buffer);
            writeChunkWithReplicas(objectId, chunkIndex, buffer);
            totalSize += buffer.length;
            chunkIndex++;
        }
        return new ChunkingResult(totalSize, chunkIndex, HexFormat.of().formatHex(wholeObjectDigest.digest()));
    }

    private record ChunkingResult(long totalSize, int chunkCount, String wholeObjectChecksum) {
    }

    private void writeChunkWithReplicas(String objectId, int chunkIndex, byte[] data) {
        String chunkId = UUID.randomUUID().toString();
        String checksum = ChecksumUtil.sha256(data);

        Set<String> attemptedWorkerIds = new HashSet<>();
        List<String> succeededWorkerIds = new ArrayList<>();
        Deque<Worker> toTry = new ArrayDeque<>(placementService.pick(Constants.REPLICATION_FACTOR));

        while (succeededWorkerIds.size() < Constants.REPLICATION_FACTOR) {
            Worker worker = toTry.isEmpty() ? placementService.pickOneNotIn(attemptedWorkerIds) : toTry.poll();
            attemptedWorkerIds.add(worker.getWorkerId());
            try {
                workerClient.putChunk(worker, chunkId, data, checksum);
                succeededWorkerIds.add(worker.getWorkerId());
            } catch (WorkerUnavailableException | ChunkRejectedException e) {
                log.warn("Failed to PUT chunk {} to worker {}, retrying with a replacement: {}",
                        chunkId, worker.getWorkerId(), e.getMessage());
            }
        }

        chunkRepository.save(new Chunk(chunkId, objectId, chunkIndex, data.length, checksum));
        for (String workerId : succeededWorkerIds) {
            replicaRepository.save(new Replica(chunkId, workerId, ReplicaState.OK));
        }
    }

    /**
     * Deletes every chunk this object wrote (from the workers that actually hold a copy) and
     * the object's rows, used both when an in-progress upload cannot complete and by the
     * startup cleanup of stale UPLOADING objects left behind by a crash.
     */
    @Transactional
    public void rollbackUpload(String objectId) {
        List<Chunk> chunks = chunkRepository.findByObjectIdOrderByChunkIndex(objectId);
        for (Chunk chunk : chunks) {
            for (Replica replica : replicaRepository.findByChunkId(chunk.getChunkId())) {
                workerRepository.findById(replica.getWorkerId())
                        .ifPresent(worker -> workerClient.deleteChunk(worker, chunk.getChunkId()));
            }
            replicaRepository.deleteByChunkId(chunk.getChunkId());
        }
        chunkRepository.deleteByObjectId(objectId);
        objectRepository.deleteById(objectId);
    }

    private Map<String, String> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(metadataJson, new TypeReference<Map<String, String>>() {
            });
            return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
        } catch (JacksonException e) {
            throw new InvalidRequestException("metadata must be a JSON object of string key-value pairs");
        }
    }
}
