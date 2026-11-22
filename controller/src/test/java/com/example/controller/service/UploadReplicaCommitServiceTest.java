package com.example.controller.service;

import com.example.common.ChecksumUtil;
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
import com.example.controller.service.UploadReplicaCommitService.UploadTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:upload-replica-commit-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UploadReplicaCommitServiceTest {

    @Autowired
    private UploadReplicaCommitService commitService;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private StoredObjectRepository objectRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ReplicaRepository replicaRepository;

    private Worker secondWorker;
    private Chunk chunk;
    private List<UploadTarget> targets;

    @BeforeEach
    void setUp() {
        Worker firstWorker = saveWorker("a-", 8082);
        secondWorker = saveWorker("b-", 8083);

        StoredObject object = new StoredObject();
        object.setId(UUID.randomUUID().toString());
        object.setName("upload-test.bin");
        object.setContentType("application/octet-stream");
        object.setStatus(ObjectStatus.UPLOADING);
        object.setCreatedAt(Instant.now());
        object.setModifiedAt(Instant.now());
        objectRepository.save(object);

        byte[] data = {1, 2, 3};

        // Metadata to publish after successful worker PUTs.
        // It is deliberately not saved here.
        chunk = new Chunk(
                UUID.randomUUID().toString(),
                object.getId(),
                0,
                data.length,
                ChecksumUtil.sha256(data));

        // Remember the registrations selected for those PUTs.
        targets = List.of(
                UploadTarget.snapshotOf(firstWorker),
                UploadTarget.snapshotOf(secondWorker));
    }

    @Test
    void unchangedRegistrationsAllowBothReplicasToBeRecorded() {
        boolean committed = commitService.tryCommit(chunk, targets);

        assertTrue(committed);

        Chunk saved = chunkRepository
                .findById(chunk.getChunkId())
                .orElseThrow();

        assertEquals(chunk.getObjectId(), saved.getObjectId());
        assertEquals(chunk.getChecksum(), saved.getChecksum());

        List<Replica> replicas = replicaRepository
                .findByChunkId(chunk.getChunkId());

        assertEquals(2, replicas.size());

        assertEquals(
                targets.stream()
                        .map(UploadTarget::workerId)
                        .sorted()
                        .toList(),
                replicas.stream()
                        .map(Replica::getWorkerId)
                        .sorted()
                        .toList());

        assertTrue(replicas.stream()
                .allMatch(replica ->
                        replica.getState() == ReplicaState.OK));
    }

    @Test
    void changedRegistrationRejectsOldResultWithoutSavingMetadata() {
        // Simulate a newer registration after the upload took
        // its snapshots. The worker is already ALIVE again.
        secondWorker.setRegistrationToken(UUID.randomUUID().toString());
        workerRepository.save(secondWorker);

        assertEquals(
                WorkerStatus.ALIVE,
                workerRepository.findById(secondWorker.getWorkerId())
                        .orElseThrow()
                        .getStatus());

        boolean committed = commitService.tryCommit(chunk, targets);

        assertFalse(committed);

        assertFalse(
                chunkRepository.existsById(chunk.getChunkId()));

        assertTrue(
                replicaRepository.findByChunkId(chunk.getChunkId())
                        .isEmpty());

        // The guard leaves upload cleanup to its caller.
        assertEquals(
                ObjectStatus.UPLOADING,
                objectRepository.findById(chunk.getObjectId())
                        .orElseThrow()
                        .getStatus());
    }

    private Worker saveWorker(String prefix, int port) {
        Worker worker = new Worker(
                prefix + UUID.randomUUID(),
                "http://localhost:" + port,
                Instant.now(),
                WorkerStatus.ALIVE,
                0,
                0);

        worker.setRegistrationToken(UUID.randomUUID().toString());

        return workerRepository.save(worker);
    }
}