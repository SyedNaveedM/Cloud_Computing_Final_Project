package com.example.controller.service;

import com.example.controller.domain.*;
import com.example.controller.repository.*;
import com.example.controller.service.RegistrationChunkVerifier.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Short metadata transactions; worker requests belong to the caller. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplicaMaintenanceService {
    private final WorkerRepository workers;
    private final StoredObjectRepository objects;
    private final ChunkRepository chunks;
    private final ReplicaRepository replicas;
    private final ChunkWriteIntentRepository intents;

    private boolean matchesWorker(Worker current, Worker expected) {
        return current != null && current.getStatus() == WorkerStatus.ALIVE
                && expected.getRegistrationToken() != null
                && Objects.equals(current.getRegistrationToken(), expected.getRegistrationToken())
                && Objects.equals(current.getUrl(), expected.getUrl());
    }

    private boolean lockCompleteChunk(Chunk expected) {
        StoredObject object = objects.findByIdForUpdate(expected.getObjectId()).orElse(null);
        if (object == null || object.getStatus() != ObjectStatus.COMPLETE) return false;
        Chunk current = chunks.findById(expected.getChunkId()).orElse(null);
        return current != null && Objects.equals(current.getObjectId(), expected.getObjectId())
                && current.getSize() == expected.getSize()
                && Objects.equals(current.getChecksum(), expected.getChecksum());
    }

    @Transactional
    public boolean observe(Chunk chunk, Worker target, Result result) {
        Worker current = workers.findByIdForUpdate(target.getWorkerId()).orElse(null);
        if (!matchesWorker(current, target) || !lockCompleteChunk(chunk)) return false;
        if (result == Result.UNAVAILABLE) return false;
        Replica replica = replicas.findByChunkIdAndWorkerId(chunk.getChunkId(), target.getWorkerId()).orElse(null);
        if (result == Result.MISSING) {
            if (replica != null) replicas.delete(replica);
        } else {
            if (replica == null) replica = new Replica(chunk.getChunkId(), target.getWorkerId(), ReplicaState.OK);
            if (result == Result.CORRUPT && replica.getState() != ReplicaState.CORRUPT)
                log.warn("Detected corrupt chunk {} on {}", chunk.getChunkId(), target.getWorkerId());
            replica.setState(result == Result.VERIFIED ? ReplicaState.OK : ReplicaState.CORRUPT);
            replicas.save(replica);
        }
        return true;
    }

    @Transactional
    public boolean beginRepair(Chunk chunk, Worker target) {
        Worker current = workers.findByIdForUpdate(target.getWorkerId()).orElse(null);
        if (!matchesWorker(current, target) || !lockCompleteChunk(chunk)) return false;
        ChunkWriteIntent intent = intents.findByChunkIdAndWorkerId(chunk.getChunkId(), target.getWorkerId()).orElse(null);
        if (intent != null && (!Objects.equals(intent.getObjectId(), chunk.getObjectId())
                || !Objects.equals(intent.getWorkerUrl(), target.getUrl()))) return false;
        if (intent == null) {
            intent = new ChunkWriteIntent();
            intent.setObjectId(chunk.getObjectId());
            intent.setChunkId(chunk.getChunkId());
            intent.setWorkerId(target.getWorkerId());
            intent.setWorkerUrl(target.getUrl());
            intent.setCreatedAt(Instant.now());
        }
        intent.setRegistrationToken(target.getRegistrationToken());
        intents.save(intent);
        return true;
    }

    @Transactional
    public boolean prepareEviction(Chunk chunk, Worker target) {
        // Registration and repair acquire worker locks before object locks.
        Map<String, Worker> locked = new HashMap<>();
        for (String id : workers.findAll().stream().map(Worker::getWorkerId).sorted().toList()) {
            workers.findByIdForUpdate(id).ifPresent(w -> locked.put(id, w));
        }
        if (!matchesWorker(locked.get(target.getWorkerId()), target) || !lockCompleteChunk(chunk)) return false;
        List<Replica> healthy = replicas.findByChunkId(chunk.getChunkId()).stream()
                .filter(r -> r.getState() == ReplicaState.OK)
                .filter(r -> locked.containsKey(r.getWorkerId())
                        && locked.get(r.getWorkerId()).getStatus() == WorkerStatus.ALIVE).toList();
        if (healthy.size() <= com.example.common.Constants.REPLICATION_FACTOR) return false;
        Replica victim = healthy.stream().filter(r -> r.getWorkerId().equals(target.getWorkerId())).findFirst().orElse(null);
        if (victim == null) return false;
        // Stop advertising this copy before evicting its bytes. A later scrub
        // revalidates it if eviction fails. Two other copies remain advertised.
        victim.setState(ReplicaState.CORRUPT);
        replicas.save(victim);
        return true;
    }
}
