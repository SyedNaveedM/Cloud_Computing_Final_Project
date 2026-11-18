package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.common.dto.ChunkInfo;
import com.example.common.dto.RegisterRequest;
import com.example.controller.client.ChunkNotFoundException;
import com.example.controller.client.WorkerClient;
import com.example.controller.client.WorkerUnavailableException;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.Replica;
import com.example.controller.domain.ReplicaState;
import com.example.controller.domain.StoredObject;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import com.example.controller.service.RegistrationCompletionService.CompletionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:registration-coordinator-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrationCoordinatorTest {

    private static final byte[] DATA = {1, 2, 3};
    private static final String CHECKSUM = ChecksumUtil.sha256(DATA);

    @Autowired
    private RegistrationCoordinator coordinator;

    @Autowired
    private RegistrationMetadataService metadataService;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ReplicaRepository replicaRepository;

    @Autowired
    private StoredObjectRepository objectRepository;

    @MockitoBean(enforceOverride = true)
    private WorkerClient workerClient;

    private String workerId;
    private RegisterRequest request;

    @BeforeEach
    void setUp() {
        workerId = "test-" + UUID.randomUUID();

        workerRepository.save(new Worker(
                workerId,
                "http://localhost:8082",
                Instant.now(),
                WorkerStatus.DEAD,
                0,
                0));

        request = new RegisterRequest(
                workerId,
                "http://localhost:8082/",
                List.of());
    }

    @Test
    void checksAnEmptyWorkerBeforeMakingItAlive() {
        stubInventory(List.of());

        assertEquals(
                CompletionResult.COMPLETED,
                coordinator.register(request));

        assertEquals(WorkerStatus.ALIVE, storedWorker().getStatus());

        verify(workerClient).listChunks(any(Worker.class));
        verify(workerClient, never())
                .getChunk(any(Worker.class), anyString());
    }

    @Test
    void verifiesMatchingMismatchedAndOmittedChunks() {
        Chunk matching = addKnownChunk(ReplicaState.CORRUPT);
        Chunk mismatched = addKnownChunk(ReplicaState.OK);
        Chunk omitted = addKnownChunk(ReplicaState.OK);
        String unknownId = UUID.randomUUID().toString();

        stubInventory(List.of(
                info(matching),
                new ChunkInfo(
                        mismatched.getChunkId(), 3, "b".repeat(64)),
                new ChunkInfo(unknownId, 3, CHECKSUM)));

        when(workerClient.getChunk(any(Worker.class), anyString()))
                .thenAnswer(invocation -> {
                    Worker target = invocation.getArgument(0);
                    String chunkId = invocation.getArgument(1);

                    assertNetworkPhase(target);

                    if (chunkId.equals(omitted.getChunkId())) {
                        throw new ChunkNotFoundException("Chunk absent");
                    }

                    if (chunkId.equals(matching.getChunkId())
                            || chunkId.equals(mismatched.getChunkId())) {
                        return DATA;
                    }

                    throw new AssertionError(
                            "Unexpected chunk request: " + chunkId);
                });

        assertEquals(
                CompletionResult.COMPLETED,
                coordinator.register(request));

        assertEquals(WorkerStatus.ALIVE, storedWorker().getStatus());
        assertEquals(ReplicaState.OK, replicaState(matching));
        assertEquals(ReplicaState.OK, replicaState(mismatched));

        assertTrue(replicaRepository.findByChunkIdAndWorkerId(
                omitted.getChunkId(), workerId).isEmpty());

        assertTrue(chunkRepository.existsById(omitted.getChunkId()));
        assertFalse(chunkRepository.existsById(unknownId));

        verify(workerClient).getChunk(
                any(Worker.class), eq(matching.getChunkId()));
        verify(workerClient).getChunk(
                any(Worker.class), eq(mismatched.getChunkId()));
        verify(workerClient).getChunk(
                any(Worker.class), eq(omitted.getChunkId()));
        verify(workerClient, never()).getChunk(
                any(Worker.class), eq(unknownId));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "inventory_timeout",
            "invalid_inventory",
            "chunk_timeout"
    })
    void failedChecksKeepWorkerRegistering(String failure) {
        Chunk chunk = addKnownChunk(ReplicaState.OK);

        switch (failure) {
            case "inventory_timeout" ->
                    when(workerClient.listChunks(any(Worker.class)))
                            .thenThrow(new WorkerUnavailableException(
                                    "Inventory request timed out"));

            case "invalid_inventory" ->
                    stubInventory(List.of(info(chunk), info(chunk)));

            case "chunk_timeout" -> {
                stubInventory(List.of(info(chunk)));

                when(workerClient.getChunk(
                        any(Worker.class), eq(chunk.getChunkId())))
                        .thenThrow(new WorkerUnavailableException(
                                "Chunk request timed out"));
            }

            default -> throw new IllegalArgumentException(failure);
        }

        assertEquals(
                CompletionResult.RETRY_REQUIRED,
                coordinator.register(request));

        assertEquals(
                WorkerStatus.REGISTERING, storedWorker().getStatus());
        assertEquals(ReplicaState.OK, replicaState(chunk));

        if (!failure.equals("chunk_timeout")) {
            verify(workerClient, never())
                    .getChunk(any(Worker.class), anyString());
        }
    }

    @Test
    void newerRegistrationMakesTheOlderCompletionStale() {
        Chunk chunk = addKnownChunk(ReplicaState.CORRUPT);
        stubInventory(List.of(info(chunk)));

        when(workerClient.getChunk(
                any(Worker.class), eq(chunk.getChunkId())))
                .thenAnswer(invocation -> {
                    assertNetworkPhase(invocation.getArgument(0));

                    // Simulate another registration arriving while
                    // the older attempt is checking this chunk.
                    metadataService.begin(new RegisterRequest(
                            workerId,
                            "http://localhost:8083",
                            List.of()));

                    return DATA;
                });

        assertEquals(
                CompletionResult.STALE_ATTEMPT,
                coordinator.register(request));

        assertEquals(
                WorkerStatus.REGISTERING, storedWorker().getStatus());
        assertEquals("http://localhost:8083", storedWorker().getUrl());
        assertEquals(ReplicaState.CORRUPT, replicaState(chunk));
    }

    private void stubInventory(List<ChunkInfo> inventory) {
        when(workerClient.listChunks(any(Worker.class)))
                .thenAnswer(invocation -> {
                    assertNetworkPhase(invocation.getArgument(0));
                    return inventory;
                });
    }

    private void assertNetworkPhase(Worker target) {
        assertFalse(
                TransactionSynchronizationManager
                        .isActualTransactionActive(),
                "Worker requests must run outside a database transaction");

        assertEquals(workerId, target.getWorkerId());
        assertEquals("http://localhost:8082", target.getUrl());

        Worker stored = storedWorker();

        assertEquals(WorkerStatus.REGISTERING, stored.getStatus());
        assertEquals(
                target.getRegistrationToken(),
                stored.getRegistrationToken());
    }

    private Chunk addKnownChunk(ReplicaState replicaState) {
        Instant now = Instant.now();

        StoredObject object = new StoredObject();
        object.setId(UUID.randomUUID().toString());
        object.setName("registration-test.bin");
        object.setContentType("application/octet-stream");
        object.setStatus(ObjectStatus.COMPLETE);
        object.setSize(DATA.length);
        object.setChunkCount(1);
        object.setChecksum(CHECKSUM);
        object.setCreatedAt(now);
        object.setModifiedAt(now);
        objectRepository.save(object);

        Chunk chunk = chunkRepository.save(new Chunk(
                UUID.randomUUID().toString(),
                object.getId(),
                0,
                DATA.length,
                CHECKSUM));

        replicaRepository.save(new Replica(
                chunk.getChunkId(), workerId, replicaState));

        return chunk;
    }

    private ChunkInfo info(Chunk chunk) {
        return new ChunkInfo(
                chunk.getChunkId(),
                chunk.getSize(),
                chunk.getChecksum());
    }

    private ReplicaState replicaState(Chunk chunk) {
        return replicaRepository.findByChunkIdAndWorkerId(
                        chunk.getChunkId(), workerId)
                .orElseThrow()
                .getState();
    }

    private Worker storedWorker() {
        return workerRepository.findById(workerId).orElseThrow();
    }
}