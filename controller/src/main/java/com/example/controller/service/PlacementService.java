package com.example.controller.service;

import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.InsufficientWorkersException;
import com.example.controller.repository.WorkerRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class PlacementService {

    private final WorkerRepository workerRepository;

    public PlacementService(WorkerRepository workerRepository) {
        this.workerRepository = workerRepository;
    }

    /**
     * Picks {@code n} distinct ALIVE workers, least-loaded first with a random tiebreak
     * among workers carrying the same chunkCount.
     */
    public List<Worker> pick(int n) {
        List<Worker> candidates = aliveWorkersLeastLoadedFirst();
        if (candidates.size() < n) {
            throw new InsufficientWorkersException(
                    "Need " + n + " ALIVE worker(s) but only " + candidates.size() + " available");
        }
        return candidates.subList(0, n);
    }

    /**
     * Picks one more ALIVE worker that is not in {@code excludedWorkerIds} (e.g. the set of
     * workers already holding/attempted for a chunk's replicas), for replication-manager use
     * and for upload retry after a failed PUT.
     */
    public Worker pickOneNotIn(Set<String> excludedWorkerIds) {
        return aliveWorkersLeastLoadedFirst().stream()
                .filter(worker -> !excludedWorkerIds.contains(worker.getWorkerId()))
                .findFirst()
                .orElseThrow(() -> new InsufficientWorkersException(
                        "No ALIVE worker available outside the excluded set " + excludedWorkerIds));
    }

    private List<Worker> aliveWorkersLeastLoadedFirst() {
        List<Worker> alive = new ArrayList<>(workerRepository.findByStatus(WorkerStatus.ALIVE));
        Collections.shuffle(alive);
        alive.sort(Comparator.comparingInt(Worker::getChunkCount));
        return alive;
    }
}
