package com.example.controller.service;

import com.example.common.dto.ChunkInfo;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.Replica;
import com.example.controller.domain.ReplicaState;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class WorkerRegistrationServiceTest {

    @Autowired
    private WorkerRegistrationService service;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ReplicaRepository replicaRepository;

    @Test
    void classifiesInventoryWithoutChangingStoredMetadata() {
        String workerId = "test-worker-" + UUID.randomUUID();

        String matchingId = addKnownChunk();
        String wrongChecksumId = addKnownChunk();
        String wrongSizeId = addKnownChunk();
        String notReportedId = addKnownChunk();
        String unknownId = UUID.randomUUID().toString();

        replicaRepository.save(
                new Replica(
                        matchingId, workerId, ReplicaState.CORRUPT));
        replicaRepository.save(
                new Replica(
                        notReportedId, workerId, ReplicaState.OK));

        var comparison = service.compareInventory(
                workerId,
                List.of(
                        new ChunkInfo(
                                matchingId, 3, "a".repeat(64)),
                        new ChunkInfo(
                                wrongChecksumId, 3, "b".repeat(64)),
                        new ChunkInfo(
                                wrongSizeId, 4, "a".repeat(64)),
                        new ChunkInfo(
                                unknownId, 3, "a".repeat(64))
                ));

        assertEquals(
                Set.of(matchingId), comparison.matching());
        assertEquals(
                Set.of(wrongChecksumId, wrongSizeId),
                comparison.mismatched());
        assertEquals(
                Set.of(notReportedId), comparison.notReported());
        assertEquals(
                Set.of(unknownId), comparison.unknown());

        assertFalse(chunkRepository.existsById(unknownId));
        assertEquals(
                2, replicaRepository.findByWorkerId(workerId).size());

        var existingReplica = replicaRepository
                .findByChunkIdAndWorkerId(matchingId, workerId)
                .orElseThrow();

        assertEquals(
                ReplicaState.CORRUPT, existingReplica.getState());
    }

    @Test
    void doesNotTreatAnotherWorkersReplicaAsNotReported() {
        String workerId = "test-worker-" + UUID.randomUUID();
        String otherWorkerId = "test-worker-" + UUID.randomUUID();

        replicaRepository.save(
                new Replica(
                        addKnownChunk(),
                        otherWorkerId,
                        ReplicaState.OK));

        var comparison =
                service.compareInventory(workerId, List.of());

        assertTrue(comparison.notReported().isEmpty());
    }

    private String addKnownChunk() {
        String chunkId = UUID.randomUUID().toString();

        chunkRepository.save(
                new Chunk(
                        chunkId,
                        UUID.randomUUID().toString(),
                        0,
                        3,
                        "a".repeat(64)));

        return chunkId;
    }
}