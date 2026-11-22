package com.example.controller.service;

import com.example.controller.domain.Replica;
import com.example.controller.domain.ReplicaState;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.InsufficientWorkersException;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.WorkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PlacementServiceTest {
    private WorkerRepository workers;
    private ReplicaRepository replicas;
    private PlacementService placement;
    private List<Worker> available;
    private Map<String, List<Replica>> recorded;

    @BeforeEach
    void setup() {
        workers = mock(WorkerRepository.class);
        replicas = mock(ReplicaRepository.class);
        placement = new PlacementService(workers, replicas);
        available = new ArrayList<>();
        recorded = new HashMap<>();
        for (int i = 1; i <= 4; i++) {
            Worker worker = new Worker("worker" + i, "http://worker" + i,
                    Instant.now(), WorkerStatus.ALIVE, 0, 0);
            available.add(worker);
        }
        when(workers.findByStatus(WorkerStatus.ALIVE)).thenAnswer(call -> available.stream()
                .filter(worker -> worker.getStatus() == WorkerStatus.ALIVE).toList());
        when(replicas.findByWorkerId(anyString())).thenAnswer(call ->
                recorded.getOrDefault(call.getArgument(0), List.of()));
    }

    @Test
    void committedReplicasBalanceRapidAssignmentsWithoutHeartbeats() {
        for (int index = 0; index < 4; index++) {
            String chunkId = "chunk-" + index;
            try (var selected = placement.reserve(chunkId, 2, Set.of())) {
                assertEquals(2, ids(selected.workers()).size());
                for (Worker worker : selected.workers()) {
                    recorded.computeIfAbsent(worker.getWorkerId(), ignored -> new ArrayList<>())
                            .add(new Replica(chunkId, worker.getWorkerId(), ReplicaState.OK));
                }
            }
        }
        assertEquals(Set.of("worker1", "worker2", "worker3", "worker4"), recorded.keySet());
        assertTrue(recorded.values().stream().allMatch(copies -> copies.size() == 2));
        assertTrue(available.stream().allMatch(worker -> worker.getChunkCount() == 0));
    }

    @Test
    void concurrentAssignmentsReserveBeforeAnyWriteCompletes() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch reserved = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<Set<String>>>();
            try {
                for (int i = 0; i < 4; i++) {
                    String chunkId = "concurrent-" + i;
                    futures.add(executor.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        try (var selected = placement.reserve(chunkId, 2, Set.of())) {
                            Set<String> holders = ids(selected.workers());
                            reserved.countDown();
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                            return holders;
                        }
                    }));
                }
                start.countDown();
                assertTrue(reserved.await(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            Map<String, Integer> counts = new HashMap<>();
            for (var future : futures) {
                Set<String> holders = future.get(5, TimeUnit.SECONDS);
                assertEquals(2, holders.size());
                holders.forEach(worker -> counts.merge(worker, 1, Integer::sum));
            }
            assertEquals(Map.of("worker1", 2, "worker2", 2, "worker3", 2, "worker4", 2), counts);
        }
    }

    @Test
    void releasedReservationDoesNotLeavePhantomLoadAndCloseIsIdempotent() {
        var abandoned = placement.reserve("abandoned", 2, Set.of());
        Set<String> held = ids(abandoned.workers());
        try (var other = placement.reserve("other", 2, Set.of())) {
            assertTrue(java.util.Collections.disjoint(held, ids(other.workers())));
            abandoned.close();
            abandoned.close();
            assertEquals(held, ids(placement.pick(2)));
        } finally {
            abandoned.close();
        }
    }

    @Test
    void commitDoesNotDoubleCountAnOpenReservation() {
        try (var first = placement.reserve("committing", 2, Set.of())) {
            Set<String> firstIds = ids(first.workers());
            for (Worker worker : first.workers()) {
                recorded.put(worker.getWorkerId(), List.of(new Replica("committing", worker.getWorkerId(), ReplicaState.OK)));
            }
            try (var second = placement.reserve("pending", 2, Set.of())) {
                Set<String> secondIds = ids(second.workers());
                assertTrue(java.util.Collections.disjoint(firstIds, secondIds));
                // Both pairs now have load one. At load two, the third pair must
                // force the fourth pair onto the remaining workers.
                try (var third = placement.reserve("third", 2, Set.of());
                     var fourth = placement.reserve("fourth", 2, Set.of())) {
                    assertTrue(java.util.Collections.disjoint(ids(third.workers()), ids(fourth.workers())));
                }
            }
        }
    }

    @Test
    void heartbeatLoadAndNewReservationsBothInfluencePlacement() {
        available.get(0).setChunkCount(10);
        available.get(1).setChunkCount(10);
        available.get(2).setChunkCount(2);
        available.get(3).setChunkCount(2);
        var held = new ArrayList<PlacementService.Reservation>();
        try {
            for (int i = 0; i < 9; i++) {
                var reservation = placement.reserve("pending-" + i, 2, Set.of());
                held.add(reservation);
                if (i < 8) assertEquals(Set.of("worker3", "worker4"), ids(reservation.workers()));
            }
            // After reaching the same load, no next assignment may choose a
            // worker already selected by the ninth reservation.
            try (var next = placement.reserve("next", 2, Set.of())) {
                assertTrue(java.util.Collections.disjoint(ids(held.get(8).workers()), ids(next.workers())));
            }
        } finally {
            held.forEach(PlacementService.Reservation::close);
        }
    }

    @Test
    void deadWorkersAndExcludedHoldersCannotBeSelected() {
        available.get(0).setStatus(WorkerStatus.DEAD);
        try (var selected = placement.reserve("replacement", 2, Set.of("worker2"))) {
            assertEquals(Set.of("worker3", "worker4"), ids(selected.workers()));
        }
        assertThrows(InsufficientWorkersException.class,
                () -> placement.reserve("impossible", 2, Set.of("worker2", "worker3")));
    }

    @Test
    void replacementControllerRebuildsLoadFromReplicaRecords() {
        for (String id : List.of("worker1", "worker2")) {
            recorded.put(id, List.of(new Replica("stored", id, ReplicaState.OK)));
        }
        PlacementService restarted = new PlacementService(workers, replicas);
        assertEquals(Set.of("worker3", "worker4"), ids(restarted.pick(2)));
        recorded.clear();
        // Simulates deletion/reconciliation: no lifetime counter keeps old load.
        available.get(2).setChunkCount(1);
        available.get(3).setChunkCount(1);
        assertEquals(Set.of("worker1", "worker2"), ids(restarted.pick(2)));
    }

    @Test
    void overlappingRepairReservationsCountTheSameChunkOnlyOnce() {
        available = new ArrayList<>(available.subList(0, 2));
        available.get(1).setChunkCount(2);
        try (var first = placement.reserveOn("repair", available.getFirst());
             var second = placement.reserveOn("repair", available.getFirst())) {
            assertEquals("worker1", placement.pick(1).getFirst().getWorkerId());
            first.close();
            assertEquals("worker1", placement.pick(1).getFirst().getWorkerId());
        }
    }

    private static Set<String> ids(List<Worker> workers) {
        return workers.stream().map(Worker::getWorkerId).collect(Collectors.toCollection(HashSet::new));
    }
}
