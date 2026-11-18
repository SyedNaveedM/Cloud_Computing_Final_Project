package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.common.dto.ChunkInfo;
import com.example.common.dto.RegisterRequest;
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
import com.example.controller.service.RegistrationChunkVerifier.Result;
import com.example.controller.service.RegistrationCompletionService.ChunkCheck;
import com.example.controller.service.RegistrationCompletionService.CompletionResult;
import com.example.controller.service.RegistrationMetadataService.RegistrationAttempt;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class RegistrationCompletionServiceTest {

    private static final String CHECKSUM =
            ChecksumUtil.sha256(new byte[]{1, 2, 3});

    @Autowired
    private RegistrationMetadataService metadataService;

    @Autowired
    private RegistrationCompletionService completionService;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ReplicaRepository replicaRepository;

    @Autowired
    private StoredObjectRepository objectRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private String workerId;
    private RegistrationAttempt attempt;

    @BeforeEach
    void startRegistration() {
        workerId = "test-" + UUID.randomUUID();

        attempt = metadataService.begin(new RegisterRequest(
                workerId,
                "http://localhost:8082",
                List.of()));
    }

    @Test
    void completedChecksUpdateReplicasAndEnableWorker() {
        Chunk recovered = addKnownChunk();
        Chunk corrupt = addKnownChunk();
        Chunk missing = addKnownChunk();
        Chunk discovered = addKnownChunk();

        addReplica(recovered, ReplicaState.CORRUPT);
        addReplica(corrupt, ReplicaState.OK);
        addReplica(missing, ReplicaState.OK);

        // "discovered" has controller chunk metadata but no replica
        // record for this worker yet.
        String unknownId = UUID.randomUUID().toString();

        var outcome = completionService.complete(
                attempt,
                List.of(
                        info(recovered),
                        info(discovered),
                        new ChunkInfo(unknownId, 3, CHECKSUM)),
                Map.of(
                        recovered.getChunkId(),
                        check(recovered, Result.VERIFIED),
                        corrupt.getChunkId(),
                        check(corrupt, Result.CORRUPT),
                        missing.getChunkId(),
                        check(missing, Result.MISSING),
                        discovered.getChunkId(),
                        check(discovered, Result.VERIFIED)));

        flushAndClear();

        assertEquals(CompletionResult.COMPLETED, outcome);
        assertEquals(WorkerStatus.ALIVE, storedWorker().getStatus());

        assertEquals(ReplicaState.OK, replicaState(recovered));
        assertEquals(ReplicaState.CORRUPT, replicaState(corrupt));
        assertEquals(ReplicaState.OK, replicaState(discovered));

        assertTrue(replicaRepository.findByChunkIdAndWorkerId(
                missing.getChunkId(), workerId).isEmpty());

        // Missing on this worker does not mean deleting global metadata.
        assertTrue(chunkRepository.existsById(missing.getChunkId()));
        assertTrue(objectRepository.existsById(missing.getObjectId()));

        // Unknown inventory entries must not create controller metadata.
        assertFalse(chunkRepository.existsById(unknownId));
        assertTrue(replicaRepository.findByChunkIdAndWorkerId(
                unknownId, workerId).isEmpty());

        assertEquals(3, replicaRepository.findByWorkerId(workerId).size());

        // Inventory counters include reported unknown chunks as load.
        assertEquals(3, storedWorker().getChunkCount());
        assertEquals(9L, storedWorker().getUsedBytes());
    }

    @Test
    void oldAttemptCannotOverwriteNewerRegistration() {
        Chunk chunk = addKnownChunk();
        addReplica(chunk, ReplicaState.CORRUPT);

        RegistrationAttempt newerAttempt =
                metadataService.begin(new RegisterRequest(
                        workerId,
                        "http://localhost:8083",
                        List.of()));

        var outcome = completionService.complete(
                attempt,
                List.of(info(chunk)),
                Map.of(chunk.getChunkId(), check(chunk, Result.VERIFIED)));

        flushAndClear();

        assertEquals(CompletionResult.STALE_ATTEMPT, outcome);
        assertEquals(WorkerStatus.REGISTERING, storedWorker().getStatus());
        assertEquals(
                newerAttempt.token(), storedWorker().getRegistrationToken());
        assertEquals("http://localhost:8083", storedWorker().getUrl());

        assertEquals(ReplicaState.CORRUPT, replicaState(chunk));
    }

    @Test
    void timeoutLeavesAllReplicaRecordsUnchanged() {
        Chunk recovered = addKnownChunk();
        Chunk unreachable = addKnownChunk();

        addReplica(recovered, ReplicaState.CORRUPT);
        addReplica(unreachable, ReplicaState.OK);

        var outcome = completionService.complete(
                attempt,
                List.of(info(recovered)),
                Map.of(
                        recovered.getChunkId(),
                        check(recovered, Result.VERIFIED),
                        unreachable.getChunkId(),
                        check(unreachable, Result.UNAVAILABLE)));

        flushAndClear();

        assertEquals(CompletionResult.RETRY_REQUIRED, outcome);
        assertEquals(WorkerStatus.REGISTERING, storedWorker().getStatus());

        // Even the successful check must not be applied in this batch.
        assertEquals(ReplicaState.CORRUPT, replicaState(recovered));

        // Timeout must not be interpreted as a missing chunk.
        assertEquals(ReplicaState.OK, replicaState(unreachable));

        assertEquals(0, storedWorker().getChunkCount());
        assertEquals(0L, storedWorker().getUsedBytes());
    }

    @Test
    void incompleteChecksLeaveWorkerRegistering() {
        Chunk recovered = addKnownChunk();
        Chunk unchecked = addKnownChunk();

        addReplica(recovered, ReplicaState.CORRUPT);
        addReplica(unchecked, ReplicaState.OK);

        var outcome = completionService.complete(
                attempt,
                List.of(info(recovered)),
                Map.of(
                        recovered.getChunkId(),
                        check(recovered, Result.VERIFIED)));

        flushAndClear();

        assertEquals(CompletionResult.RETRY_REQUIRED, outcome);
        assertEquals(WorkerStatus.REGISTERING, storedWorker().getStatus());

        assertEquals(ReplicaState.CORRUPT, replicaState(recovered));
        assertEquals(ReplicaState.OK, replicaState(unchecked));
        assertEquals(0, storedWorker().getChunkCount());
    }

    @Test
    void lateVerificationDoesNotRecreateDeletedObject() {
        Chunk chunk = addKnownChunk();
        addReplica(chunk, ReplicaState.OK);

        ChunkCheck earlierVerification = check(chunk, Result.VERIFIED);
        ChunkInfo earlierInventoryEntry = info(chunk);

        // Simulate metadata already removed by a completed deletion.
        replicaRepository.deleteByChunkId(chunk.getChunkId());
        chunkRepository.deleteByObjectId(chunk.getObjectId());
        objectRepository.deleteById(chunk.getObjectId());
        flushAndClear();

        var outcome = completionService.complete(
                attempt,
                List.of(earlierInventoryEntry),
                Map.of(chunk.getChunkId(), earlierVerification));

        flushAndClear();

        assertEquals(CompletionResult.COMPLETED, outcome);
        assertEquals(WorkerStatus.ALIVE, storedWorker().getStatus());

        assertFalse(objectRepository.existsById(chunk.getObjectId()));
        assertFalse(chunkRepository.existsById(chunk.getChunkId()));
        assertTrue(replicaRepository.findByChunkIdAndWorkerId(
                chunk.getChunkId(), workerId).isEmpty());
    }

    private Chunk addKnownChunk() {
        Instant now = Instant.now();

        StoredObject object = new StoredObject();
        object.setId(UUID.randomUUID().toString());
        object.setName("registration-test.bin");
        object.setContentType("application/octet-stream");
        object.setStatus(ObjectStatus.COMPLETE);
        object.setSize(3);
        object.setChunkCount(1);
        object.setChecksum(CHECKSUM);
        object.setCreatedAt(now);
        object.setModifiedAt(now);
        objectRepository.save(object);

        return chunkRepository.save(new Chunk(
                UUID.randomUUID().toString(),
                object.getId(),
                0,
                3,
                CHECKSUM));
    }

    private void addReplica(Chunk chunk, ReplicaState state) {
        replicaRepository.save(new Replica(
                chunk.getChunkId(), workerId, state));
    }

    private ChunkInfo info(Chunk chunk) {
        return new ChunkInfo(
                chunk.getChunkId(),
                chunk.getSize(),
                chunk.getChecksum());
    }

    private ChunkCheck check(Chunk chunk, Result result) {
        return new ChunkCheck(
                chunk.getObjectId(),
                chunk.getSize(),
                chunk.getChecksum(),
                result);
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

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
