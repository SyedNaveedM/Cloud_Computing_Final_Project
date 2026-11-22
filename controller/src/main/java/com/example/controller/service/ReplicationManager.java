package com.example.controller.service;

import com.example.controller.client.*;
import com.example.controller.domain.*;
import com.example.controller.exception.InsufficientWorkersException;
import com.example.controller.repository.*;
import com.example.controller.service.RegistrationChunkVerifier.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReplicationManager {
    private final StoredObjectRepository objects;
    private final ChunkRepository chunks;
    private final ReplicaRepository replicas;
    private final ChunkWriteIntentRepository intents;
    private final WorkerRepository workers;
    private final WorkerClient client;
    private final RegistrationChunkVerifier verifier;
    private final ReplicaMaintenanceService metadata;
    private final PlacementService placement;
    private final DownloadService download;

    /** One controller runs one maintenance pass at a time. */
    public synchronized void runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Replica maintenance must run outside a database transaction");
        }
        // Keep the pass outside transaction synchronization as well. A surrounding
        // NEVER scope can retain an EntityManager with stale replicas after the
        // short metadata transactions have committed their updated states.
        for (StoredObject object : objects.findByStatus(ObjectStatus.COMPLETE)) {
            for (Chunk chunk : chunks.findByObjectIdOrderByChunkIndex(object.getId())) {
                try { maintain(chunk); }
                catch (RuntimeException error) {
                    log.warn("Maintenance of chunk {} will retry: {}", chunk.getChunkId(), error.getMessage());
                }
            }
        }
    }

    private void maintain(Chunk chunk) {
        Set<String> locations = new TreeSet<>();
        replicas.findByChunkId(chunk.getChunkId()).forEach(r -> locations.add(r.getWorkerId()));
        // Includes writes whose acknowledgement or metadata commit was lost.
        intents.findByChunkId(chunk.getChunkId()).forEach(i -> locations.add(i.getWorkerId()));
        for (String id : locations) {
            Worker worker = workers.findById(id).orElse(null);
            if (worker == null || worker.getStatus() != WorkerStatus.ALIVE) continue;
            Result result = verifier.verify(worker, chunk);
            metadata.observe(chunk, worker, result);
        }

        Set<String> attempted = new HashSet<>(healthyIds(chunk));
        byte[] data = null;
        while (healthyIds(chunk).size() < com.example.common.Constants.REPLICATION_FACTOR) {
            PlacementService.Reservation reservation;
            try {
                // Prefer replacing a known corrupt copy before using extra space.
                Worker corrupt = replicas.findByChunkId(chunk.getChunkId()).stream()
                        .filter(r -> r.getState() == ReplicaState.CORRUPT && !attempted.contains(r.getWorkerId()))
                        .map(r -> workers.findById(r.getWorkerId()).orElse(null))
                        .filter(w -> w != null && w.getStatus() == WorkerStatus.ALIVE)
                        .findFirst().orElse(null);
                reservation = corrupt == null
                        ? placement.reserve(chunk.getChunkId(), 1, attempted)
                        : placement.reserveOn(chunk.getChunkId(), corrupt);
            }
            catch (InsufficientWorkersException noTarget) { break; }
            try (reservation) {
                Worker target = reservation.workers().getFirst();
                attempted.add(target.getWorkerId());
                if (data == null) data = download.fetchVerified(chunk);
                if (!metadata.beginRepair(chunk, target)) continue;
                try {
                    client.putChunk(target, chunk.getChunkId(), data, chunk.getChecksum());
                    // Do not publish a replica without checking its stored bytes.
                    Result result = verifier.verify(target, chunk);
                    if (metadata.observe(chunk, target, result) && result == Result.VERIFIED)
                        log.info("Restored replica {} on {}", chunk.getChunkId(), target.getWorkerId());
                } catch (WorkerUnavailableException | ChunkRejectedException error) {
                    log.warn("Repair of {} on {} will retry: {}", chunk.getChunkId(), target.getWorkerId(), error.getMessage());
                }
            }
        }

        // A returning worker can create a temporary third healthy copy.
        List<String> healthy = new ArrayList<>(healthyIds(chunk));
        Collections.sort(healthy);
        for (int i = healthy.size() - 1; i >= com.example.common.Constants.REPLICATION_FACTOR; i--) {
            Worker target = workers.findById(healthy.get(i)).orElse(null);
            if (target == null || !metadata.prepareEviction(chunk, target)) continue;
            try {
                client.evictChunk(target, chunk.getChunkId());
                metadata.observe(chunk, target, Result.MISSING);
            } catch (RuntimeException error) {
                log.warn("Excess replica {} on {} remains for retry: {}", chunk.getChunkId(), target.getWorkerId(), error.getMessage());
            }
        }
    }

    private Set<String> healthyIds(Chunk chunk) {
        Set<String> result = new HashSet<>();
        for (Replica replica : replicas.findByChunkId(chunk.getChunkId())) {
            if (replica.getState() == ReplicaState.OK && workers.findById(replica.getWorkerId())
                    .map(w -> w.getStatus() == WorkerStatus.ALIVE).orElse(false)) result.add(replica.getWorkerId());
        }
        return result;
    }
}
