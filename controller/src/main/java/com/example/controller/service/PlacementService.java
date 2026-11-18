package com.example.controller.service;

import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.InsufficientWorkersException;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.WorkerRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Placement accounting for the project's single controller. */
@Service
public class PlacementService {

    private final WorkerRepository workerRepository;
    private final ReplicaRepository replicaRepository;
    // worker -> chunk -> number of active reservations for that pair.
    private final Map<String, Map<String, Integer>> reservations = new HashMap<>();

    public PlacementService(WorkerRepository workerRepository, ReplicaRepository replicaRepository) {
        this.workerRepository = workerRepository;
        this.replicaRepository = replicaRepository;
    }

    /** Retained selection API; writers use reserve() to cover concurrent assignments. */
    public synchronized List<Worker> pick(int n) {
        return select(n, Set.of());
    }

    public synchronized Worker pickOneNotIn(Set<String> excludedWorkerIds) {
        return select(1, excludedWorkerIds).getFirst();
    }

    /** Select and reserve atomically, before any worker network requests. */
    public synchronized Reservation reserve(String chunkId, int n, Set<String> excludedWorkerIds) {
        requireChunkId(chunkId);
        return record(chunkId, select(n, excludedWorkerIds));
    }

    /** Repair may prefer replacing a known corrupt copy on its existing worker. */
    synchronized Reservation reserveOn(String chunkId, Worker worker) {
        requireChunkId(chunkId);
        Objects.requireNonNull(worker, "Worker is required");
        return record(chunkId, List.of(worker));
    }

    private List<Worker> select(int n, Set<String> excludedWorkerIds) {
        if (n <= 0) throw new IllegalArgumentException("Replica count must be positive");
        Objects.requireNonNull(excludedWorkerIds, "Excluded workers are required");
        List<Worker> candidates = new ArrayList<>(workerRepository.findByStatus(WorkerStatus.ALIVE));
        candidates.removeIf(worker -> excludedWorkerIds.contains(worker.getWorkerId()));
        if (candidates.size() < n) {
            throw new InsufficientWorkersException(
                    "Need " + n + " distinct ALIVE worker(s) outside the excluded set, but only "
                            + candidates.size() + " available");
        }

        Map<String, Long> loads = new HashMap<>();
        for (Worker worker : candidates) {
            Set<String> recorded = new HashSet<>();
            replicaRepository.findByWorkerId(worker.getWorkerId())
                    .forEach(replica -> recorded.add(replica.getChunkId()));
            long pending = reservations.getOrDefault(worker.getWorkerId(), Map.of()).keySet().stream()
                    .filter(chunkId -> !recorded.contains(chunkId)).count();
            // Heartbeats also cover physical data not yet represented by replica records.
            // A committed replica replaces its reservation in this count, never adds to it.
            long stored = Math.max(Math.max(0, worker.getChunkCount()), recorded.size());
            loads.put(worker.getWorkerId(), stored + pending);
        }
        Collections.shuffle(candidates);
        candidates.sort(Comparator.comparingLong(worker -> loads.get(worker.getWorkerId())));
        return List.copyOf(candidates.subList(0, n));
    }

    private Reservation record(String chunkId, List<Worker> workers) {
        for (Worker worker : workers) {
            reservations.computeIfAbsent(worker.getWorkerId(), ignored -> new HashMap<>())
                    .merge(chunkId, 1, Integer::sum);
        }
        return new Reservation(chunkId, workers);
    }

    private static void requireChunkId(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) throw new IllegalArgumentException("Chunk ID is required");
    }

    /** Keep open until replica metadata commits or the attempt is abandoned. */
    public final class Reservation implements AutoCloseable {
        private final String chunkId;
        private final List<Worker> workers;
        private boolean closed;

        private Reservation(String chunkId, List<Worker> workers) {
            this.chunkId = chunkId;
            this.workers = List.copyOf(workers);
        }

        public List<Worker> workers() {
            return workers;
        }

        @Override
        public void close() {
            synchronized (PlacementService.this) {
                if (closed) return;
                for (Worker worker : workers) {
                    Map<String, Integer> chunks = reservations.get(worker.getWorkerId());
                    int remaining = chunks.get(chunkId) - 1;
                    if (remaining == 0) chunks.remove(chunkId);
                    else chunks.put(chunkId, remaining);
                    if (chunks.isEmpty()) reservations.remove(worker.getWorkerId());
                }
                closed = true;
            }
        }
    }
}
