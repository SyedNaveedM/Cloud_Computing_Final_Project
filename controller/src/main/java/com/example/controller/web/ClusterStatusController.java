package com.example.controller.web;

import com.example.controller.domain.*;
import com.example.controller.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class ClusterStatusController {
    private final WorkerRepository workers;
    private final StoredObjectRepository objects;
    private final ChunkRepository chunks;
    private final ReplicaRepository replicas;
    private final ChunkDeletionJobRepository jobs;

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP"); }

    @GetMapping("/status")
    public ClusterStatus status() {
        Map<String, Worker> byId = new HashMap<>();
        workers.findAll().forEach(w -> byId.put(w.getWorkerId(), w));
        List<WorkerView> workerViews = byId.values().stream()
                .sorted(Comparator.comparing(Worker::getWorkerId))
                .map(w -> new WorkerView(w.getWorkerId(), w.getUrl(), w.getStatus().name(),
                        w.getLastHeartbeat(), w.getUsedBytes(), w.getChunkCount())).toList();
        List<ChunkView> chunkViews = new ArrayList<>();
        for (StoredObject object : objects.findByStatus(ObjectStatus.COMPLETE)) {
            for (Chunk chunk : chunks.findByObjectIdOrderByChunkIndex(object.getId())) {
                List<ReplicaView> locations = replicas.findByChunkId(chunk.getChunkId()).stream()
                        .map(r -> new ReplicaView(r.getWorkerId(), r.getState().name(),
                                r.getState() == ReplicaState.OK && byId.containsKey(r.getWorkerId())
                                        && byId.get(r.getWorkerId()).getStatus() == WorkerStatus.ALIVE)).toList();
                chunkViews.add(new ChunkView(object.getId(), chunk.getChunkId(), chunk.getChunkIndex(),
                        locations.stream().filter(ReplicaView::usable).count(), locations));
            }
        }
        return new ClusterStatus(workerViews, chunkViews, jobs.count(),
                chunkViews.stream().filter(c -> c.healthyCopies() < 2).count());
    }

    public record WorkerView(String workerId, String url, String status, Instant lastHeartbeat, long usedBytes, int chunkCount) { }
    public record ReplicaView(String workerId, String state, boolean usable) { }
    public record ChunkView(String objectId, String chunkId, int chunkIndex, long healthyCopies, List<ReplicaView> replicas) { }
    public record ClusterStatus(List<WorkerView> workers, List<ChunkView> chunks, long pendingDeletions, long underReplicatedChunks) { }
}
