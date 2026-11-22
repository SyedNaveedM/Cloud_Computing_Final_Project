package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.controller.client.*;
import com.example.controller.domain.*;
import com.example.controller.exception.*;
import com.example.controller.repository.*;
import com.example.controller.service.RegistrationChunkVerifier.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DownloadService {
    private final StoredObjectRepository objectRepository;
    private final ChunkRepository chunkRepository;
    private final ReplicaRepository replicaRepository;
    private final WorkerRepository workerRepository;
    private final WorkerClient workerClient;
    private final ReplicaMaintenanceService maintenance;

    public StoredObject getCompleteObjectOrThrow(String objectId) {
        StoredObject object = objectRepository.findById(objectId)
                .orElseThrow(() -> new ObjectNotFoundException(objectId));
        if (object.getStatus() != ObjectStatus.COMPLETE) throw new ObjectNotFoundException(objectId);
        return object;
    }

    public void preflightCheck(String objectId) {
        StoredObject object = getCompleteObjectOrThrow(objectId);
        List<Chunk> chunks = chunkRepository.findByObjectIdOrderByChunkIndex(objectId);
        if (chunks.size() != object.getChunkCount()
                || chunks.stream().mapToLong(Chunk::getSize).sum() != object.getSize()) {
            throw new ObjectUnavailableException("Object chunk metadata is incomplete");
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).getChunkIndex() != i) throw new ObjectUnavailableException("Object chunk ordering is invalid");
            try { fetchVerified(chunks.get(i)); }
            catch (ChunkUnavailableException error) {
                throw new ObjectUnavailableException(error.getMessage());
            }
        }
    }

    public void streamObject(String objectId, OutputStream out) throws IOException {
        for (Chunk chunk : chunkRepository.findByObjectIdOrderByChunkIndex(objectId)) out.write(fetchVerified(chunk));
    }

    /** Signature retained for repair and Person A's callers. */
    public byte[] fetchVerified(Chunk chunk) {
        List<Replica> candidates = new ArrayList<>(replicaRepository.findByChunkId(chunk.getChunkId()));
        Collections.shuffle(candidates);
        for (Replica replica : candidates) {
            if (replica.getState() != ReplicaState.OK) continue;
            Worker worker = workerRepository.findById(replica.getWorkerId()).orElse(null);
            if (worker == null || worker.getStatus() != WorkerStatus.ALIVE) continue;
            try {
                byte[] data = workerClient.getChunk(worker, chunk.getChunkId());
                if (data != null && data.length == chunk.getSize()
                        && ChecksumUtil.sha256(data).equalsIgnoreCase(chunk.getChecksum())) {
                    log.info("Served chunk {} from worker {}", chunk.getChunkId(), worker.getWorkerId());
                    return data;
                }
                maintenance.observe(chunk, worker, Result.CORRUPT);
            } catch (ChunkNotFoundException error) {
                maintenance.observe(chunk, worker, Result.MISSING);
            } catch (CorruptChunkException error) {
                maintenance.observe(chunk, worker, Result.CORRUPT);
            } catch (WorkerUnavailableException error) {
                // A timeout is not evidence of corruption. Try another copy.
            }
        }
        throw new ChunkUnavailableException("No healthy replica available for chunk " + chunk.getChunkId());
    }
}
