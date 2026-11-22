package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.controller.domain.*;
import com.example.controller.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:upload-cleanup-rollback-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UploadCleanupRollbackTest {

    @Autowired
    private UploadCleanupService cleanupService;

    @Autowired
    private StoredObjectRepository objectRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ReplicaRepository replicaRepository;

    @Autowired
    private ChunkWriteIntentRepository intentRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @MockitoSpyBean
    private ChunkDeletionJobRepository deletionJobRepository;

    @Test
    void secondJobFailureRollsBackFirstJobAndPreservesUploadRecords() {
        // The test itself must not supply the cleanup transaction.
        assertFalse(
                TransactionSynchronizationManager
                        .isActualTransactionActive());

        String objectId = UUID.randomUUID().toString();
        String chunkId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        byte[] data = new byte[] {1, 2, 3, 4};
        String checksum = ChecksumUtil.sha256(data);

        // Prepare an incomplete upload with one recorded chunk.
        StoredObject object = new StoredObject();
        object.setId(objectId);
        object.setName("cleanup-rollback.bin");
        object.setContentType("application/octet-stream");
        object.setCreatedAt(now);
        object.setModifiedAt(now);
        object.setStatus(ObjectStatus.UPLOADING);

        objectRepository.save(object);

        chunkRepository.save(new Chunk(
                chunkId,
                objectId,
                0,
                data.length,
                checksum));

        // Record two workers, two replicas, and two write intents.
        for (int number = 1; number <= 2; number++) {
            String workerId = "cleanup-worker-" + number;

            Worker worker = new Worker(
                    workerId,
                    "http://localhost:" + (8081 + number),
                    now,
                    WorkerStatus.ALIVE,
                    data.length,
                    1);

            worker.setRegistrationToken(UUID.randomUUID().toString());
            workerRepository.save(worker);

            replicaRepository.save(new Replica(
                    chunkId,
                    workerId,
                    ReplicaState.OK));

            ChunkWriteIntent intent = new ChunkWriteIntent();
            intent.setObjectId(objectId);
            intent.setChunkId(chunkId);
            intent.setWorkerId(workerId);
            intent.setWorkerUrl(worker.getUrl());
            intent.setRegistrationToken(worker.getRegistrationToken());
            intent.setCreatedAt(now);

            intentRepository.save(intent);
        }

        assertEquals(0L, deletionJobRepository.count());

        AtomicInteger saveAttempts = new AtomicInteger();

        doAnswer(invocation -> {
            assertTrue(
                    TransactionSynchronizationManager
                            .isActualTransactionActive(),
                    "Deletion jobs must be saved inside a transaction");

            int attempt = saveAttempts.incrementAndGet();

            if (attempt == 1) {
                ChunkDeletionJob job = invocation.getArgument(0);

                // Execute a real INSERT in the current transaction.
                // Flushing does not commit that transaction.
                ChunkDeletionJob saved =
                        deletionJobRepository.saveAndFlush(job);

                assertNotNull(saved.getId());

                assertEquals(
                        1L,
                        deletionJobRepository.count(),
                        "The first job must reach the database before failure");

                return saved;
            }

            // Fail while attempting to save the second job.
            throw new DataAccessResourceFailureException(
                    "Simulated failure saving the second deletion job");

        }).when(deletionJobRepository).save(
                any(ChunkDeletionJob.class));

        assertThrows(
                DataAccessResourceFailureException.class,
                () -> cleanupService.removeIncompleteUpload(objectId));

        assertEquals(
                2,
                saveAttempts.get(),
                "The failure must happen on the second save");

        // The service transaction has now ended.
        assertFalse(
                TransactionSynchronizationManager
                        .isActualTransactionActive());

        // Even the first INSERT must have been rolled back.
        assertEquals(
                0L,
                deletionJobRepository.count(),
                "No partially committed deletion jobs may remain");

        // The original upload information must still be available.
        StoredObject remainingObject = objectRepository
                .findById(objectId)
                .orElseThrow();

        assertEquals(
                ObjectStatus.UPLOADING,
                remainingObject.getStatus());

        assertTrue(chunkRepository.existsById(chunkId));

        assertEquals(
                2,
                replicaRepository.findByChunkId(chunkId).size());

        var remainingIntents =
                intentRepository.findByObjectId(objectId);

        assertEquals(2, remainingIntents.size());

        assertEquals(
                Set.of("cleanup-worker-1", "cleanup-worker-2"),
                remainingIntents.stream()
                        .map(ChunkWriteIntent::getWorkerId)
                        .collect(Collectors.toSet()));
    }
}