package com.example.controller.repository;

import com.example.controller.domain.Replica;
import com.example.controller.domain.ReplicaState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class ReplicaUniquenessTest {

    @Autowired
    private ReplicaRepository replicaRepository;

    @Test
    void rejectsDuplicateChunkOnSameWorker() {
        String chunkId = UUID.randomUUID().toString();

        replicaRepository.saveAndFlush(
                new Replica(chunkId, "worker1", ReplicaState.OK));

        assertThrows(DataIntegrityViolationException.class,
                () -> replicaRepository.saveAndFlush(
                        new Replica(chunkId, "worker1", ReplicaState.OK)));
    }

    @Test
    void allowsSameChunkOnDifferentWorkers() {
        String chunkId = UUID.randomUUID().toString();

        replicaRepository.saveAndFlush(
                new Replica(chunkId, "worker1", ReplicaState.OK));
        replicaRepository.saveAndFlush(
                new Replica(chunkId, "worker2", ReplicaState.OK));

        assertEquals(
                2, replicaRepository.findByChunkId(chunkId).size());
    }

    @Test
    void allowsDifferentChunksOnSameWorker() {
        String workerId = "test-worker-" + UUID.randomUUID();

        replicaRepository.saveAndFlush(
                new Replica(
                        UUID.randomUUID().toString(),
                        workerId,
                        ReplicaState.OK));
        replicaRepository.saveAndFlush(
                new Replica(
                        UUID.randomUUID().toString(),
                        workerId,
                        ReplicaState.OK));

        assertEquals(
                2, replicaRepository.findByWorkerId(workerId).size());
    }
}