package com.example.controller.service;

import com.example.common.Constants;
import com.example.controller.client.WorkerClient;
import com.example.controller.client.WorkerUnavailableException;
import com.example.controller.domain.*;
import com.example.controller.exception.InsufficientWorkersException;
import com.example.controller.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:placement-integration;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "objectstore.maintenance.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlacementIntegrationTest {
    @Autowired UploadService upload;
    @Autowired PlacementService placement;
    @Autowired WorkerRepository workers;
    @Autowired StoredObjectRepository objects;
    @Autowired ChunkRepository chunks;
    @Autowired ReplicaRepository replicas;
    @Autowired ChunkWriteIntentRepository intents;
    @Autowired ChunkDeletionJobRepository jobs;
    @MockitoBean(enforceOverride = true) WorkerClient client;

    @BeforeEach
    void resetDatabase() {
        jobs.deleteAll(); intents.deleteAll(); replicas.deleteAll();
        chunks.deleteAll(); objects.deleteAll(); workers.deleteAll();
        for (int i = 1; i <= 4; i++) {
            Worker worker = new Worker("worker" + i, "http://test-worker" + i,
                    Instant.now(), WorkerStatus.ALIVE, 0, 0);
            worker.setRegistrationToken(UUID.randomUUID().toString());
            workers.save(worker);
        }
    }

    @Test
    void fourChunkUploadUsesEachInitiallyEmptyWorkerTwiceWithoutHeartbeats() {
        StoredObject object = upload(new byte[3 * Constants.CHUNK_SIZE + 17]);
        assertEquals(ObjectStatus.COMPLETE, object.getStatus());
        assertEquals(4, object.getChunkCount());
        for (Worker worker : workers.findAll()) {
            assertEquals(0, worker.getChunkCount(), "No heartbeat update is involved in this test");
            assertEquals(2, replicas.findByWorkerId(worker.getWorkerId()).size());
        }
        for (Chunk chunk : chunks.findByObjectIdOrderByChunkIndex(object.getId())) {
            assertEquals(2, replicas.findByChunkId(chunk.getChunkId()).stream()
                    .map(Replica::getWorkerId).distinct().count());
        }
    }

    @Test
    void anotherUploadUsesUnreservedWorkersWhileFirstPutIsBlocked() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        doAnswer(call -> {
            if (firstCall.compareAndSet(true, false)) {
                entered.countDown();
                assertTrue(release.await(15, TimeUnit.SECONDS));
            }
            return null;
        }).when(client).putChunk(any(), anyString(), any(), anyString());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> upload(new byte[]{1}));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                StoredObject second = executor.submit(() -> upload(new byte[]{2})).get(10, TimeUnit.SECONDS);
                assertFalse(first.isDone(), "First upload must still be waiting for its worker");
                release.countDown();
                StoredObject firstObject = first.get(5, TimeUnit.SECONDS);
                assertTrue(java.util.Collections.disjoint(holders(firstObject), holders(second)));
                assertEquals(4, replicas.count());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void failedWorkerIsReplacedWithoutDuplicatingAHolder() {
        AtomicBoolean failFirst = new AtomicBoolean(true);
        doAnswer(call -> {
            if (failFirst.compareAndSet(true, false)) throw new WorkerUnavailableException("test outage");
            return null;
        }).when(client).putChunk(any(), anyString(), any(), anyString());
        StoredObject object = upload(new byte[]{1, 2, 3});
        assertEquals(2, holders(object).size());
        verify(client, times(3)).putChunk(any(), anyString(), any(), anyString());
    }

    @Test
    void registrationChangeBeforeCommitReleasesReservations() {
        // These workers must be preferred again after abort; workers 3/4 have
        // one committed replica and one extra reservation during the assertion.
        for (String id : List.of("worker3", "worker4")) {
            replicas.save(new Replica("existing-" + id, id, ReplicaState.OK));
        }
        doAnswer(call -> {
            Worker target = call.getArgument(0);
            Worker current = workers.findById(target.getWorkerId()).orElseThrow();
            current.setRegistrationToken(UUID.randomUUID().toString());
            workers.save(current);
            return null;
        }).when(client).putChunk(any(), anyString(), any(), anyString());
        assertThrows(InsufficientWorkersException.class, () -> upload(new byte[]{1}));
        // With no leaked reservation, first pair is workers 1/2 and the next
        // three assignments balance to two units on each worker.
        try (var first = placement.reserve("after-failure-1", 2, Set.of())) {
            assertEquals(Set.of("worker1", "worker2"), ids(first.workers()));
            try (var second = placement.reserve("after-failure-2", 2, Set.of());
                 var third = placement.reserve("after-failure-3", 2, Set.of())) {
                assertTrue(java.util.Collections.disjoint(ids(second.workers()), ids(third.workers())));
            }
        }
    }

    private StoredObject upload(byte[] bytes) {
        return upload.upload(new ByteArrayInputStream(bytes), "placement.bin", "application/octet-stream", null);
    }

    private Set<String> holders(StoredObject object) {
        Chunk chunk = chunks.findByObjectIdOrderByChunkIndex(object.getId()).getFirst();
        return replicas.findByChunkId(chunk.getChunkId()).stream().map(Replica::getWorkerId).collect(Collectors.toSet());
    }

    private static Set<String> ids(List<Worker> workers) {
        return workers.stream().map(Worker::getWorkerId).collect(Collectors.toSet());
    }
}
