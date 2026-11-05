package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.controller.client.CorruptChunkException;
import com.example.controller.client.WorkerClient;
import com.example.controller.client.WorkerUnavailableException;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.Replica;
import com.example.controller.domain.ReplicaState;
import com.example.controller.domain.StoredObject;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.ChunkUnavailableException;
import com.example.controller.exception.ObjectNotFoundException;
import com.example.controller.exception.ObjectUnavailableException;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class DownloadService {

    private final StoredObjectRepository objectRepository;
    private final ChunkRepository chunkRepository;
    private final ReplicaRepository replicaRepository;
    private final WorkerRepository workerRepository;
    private final WorkerClient workerClient;

    public DownloadService(StoredObjectRepository objectRepository,
                            ChunkRepository chunkRepository,
                            ReplicaRepository replicaRepository,
                            WorkerRepository workerRepository,
                            WorkerClient workerClient) {
        this.objectRepository = objectRepository;
        this.chunkRepository = chunkRepository;
        this.replicaRepository = replicaRepository;
        this.workerRepository = workerRepository;
        this.workerClient = workerClient;
    }

    public StoredObject getCompleteObjectOrThrow(String objectId) {
        StoredObject object = objectRepository.findById(objectId)
                .orElseThrow(() -> new ObjectNotFoundException(objectId));
        if (object.getStatus() != ObjectStatus.COMPLETE) {
            throw new ObjectNotFoundException(objectId);
        }
        return object;
    }

    /**
     * Every chunk must have at least one OK replica on an ALIVE worker before any bytes are
     * sent, since the HTTP status line commits as soon as streaming starts.
     */
    public void preflightCheck(String objectId) {
        for (Chunk chunk : chunkRepository.findByObjectIdOrderByChunkIndex(objectId)) {
            boolean hasHealthyReplica = replicaRepository.findByChunkId(chunk.getChunkId()).stream()
                    .anyMatch(this::isHealthy);
            if (!hasHealthyReplica) {
                throw new ObjectUnavailableException(
                        "Chunk " + chunk.getChunkId() + " of object " + objectId + " has no OK replica on an ALIVE worker");
            }
        }
    }

    public void streamObject(String objectId, OutputStream out) throws IOException {
        for (Chunk chunk : chunkRepository.findByObjectIdOrderByChunkIndex(objectId)) {
            out.write(fetchVerified(chunk));
        }
    }

    /**
     * Tries OK replicas on ALIVE workers in random order; the Controller's own checksum is
     * authoritative, so a replica that returns the wrong bytes is marked CORRUPT and skipped
     * in favor of the next one, rather than ever being handed to the client.
     */
    public byte[] fetchVerified(Chunk chunk) {
        List<Replica> candidates = new ArrayList<>(replicaRepository.findByChunkId(chunk.getChunkId()).stream()
                .filter(this::isHealthy)
                .toList());
        Collections.shuffle(candidates);

        for (Replica replica : candidates) {
            Worker worker = workerRepository.findById(replica.getWorkerId()).orElse(null);
            if (worker == null) {
                continue;
            }
            try {
                byte[] data = workerClient.getChunk(worker, chunk.getChunkId());
                if (ChecksumUtil.sha256(data).equals(chunk.getChecksum())) {
                    return data;
                }
                markCorrupt(replica);
            } catch (CorruptChunkException e) {
                markCorrupt(replica);
            } catch (WorkerUnavailableException e) {
                // try the next replica
            }
        }
        throw new ChunkUnavailableException("No healthy replica available for chunk " + chunk.getChunkId());
    }

    private boolean isHealthy(Replica replica) {
        if (replica.getState() != ReplicaState.OK) {
            return false;
        }
        return workerRepository.findById(replica.getWorkerId())
                .map(worker -> worker.getStatus() == WorkerStatus.ALIVE)
                .orElse(false);
    }

    private void markCorrupt(Replica replica) {
        replica.setState(ReplicaState.CORRUPT);
        replicaRepository.save(replica);
    }
}
