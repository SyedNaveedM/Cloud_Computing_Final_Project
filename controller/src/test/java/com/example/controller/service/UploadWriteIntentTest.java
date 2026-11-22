package com.example.controller.service;

import com.example.controller.client.WorkerClient;
import com.example.controller.domain.ChunkWriteIntent;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.StoredObject;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.repository.ChunkWriteIntentRepository;
import com.example.controller.repository.WorkerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.example.controller.domain.ChunkDeletionJob;
import com.example.controller.repository.ChunkDeletionJobRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.controller.client.WorkerUnavailableException;
import com.example.controller.exception.InsufficientWorkersException;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.StoredObjectRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:upload-write-intent-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class UploadWriteIntentTest {
        @Autowired
private ChunkDeletionJobRepository deletionJobRepository;
    @Autowired
    private UploadService uploadService;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private ChunkWriteIntentRepository intentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private StoredObjectRepository objectRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ReplicaRepository replicaRepository;

    @MockitoBean(enforceOverride = true)
    private WorkerClient workerClient;

    @Test
    void intentIsCommittedBeforeEachWorkerPut() {
        saveWorker("a-", 8082);
        saveWorker("b-", 8083);

        TransactionTemplate readTransaction =
                new TransactionTemplate(transactionManager);

        readTransaction.setReadOnly(true);
        readTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);

        Set<String> workersSeen = new HashSet<>();
        Set<String> objectIdsSeen = new HashSet<>();

        doAnswer(invocation -> {
            // This callback runs when the upload reaches a worker PUT.
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive(),
                    "Worker PUT must start outside a database transaction");

            Worker target = invocation.getArgument(0);
            String chunkId = invocation.getArgument(1);

            // Read from a fresh transaction that cannot see
            // another transaction's uncommitted writes.
            ChunkWriteIntent intent = readTransaction.execute(status -> {
                assertTrue(
                        status.isNewTransaction(),
                        "The verification must use a fresh transaction");

                return intentRepository
                        .findByChunkIdAndWorkerId(
                                chunkId,
                                target.getWorkerId())
                        .orElseThrow(() -> new AssertionError(
                                "No committed write intent exists "
                                + "when the worker PUT begins"));
            });

            assertNotNull(intent);
            assertEquals(target.getUrl(), intent.getWorkerUrl());
            assertEquals(
                    target.getRegistrationToken(),
                    intent.getRegistrationToken());

            assertTrue(
                    workersSeen.add(target.getWorkerId()),
                    "This successful upload should use distinct workers");

            objectIdsSeen.add(intent.getObjectId());

            // Simulate a successful worker response.
            return null;
        }).when(workerClient).putChunk(
                any(Worker.class),
                anyString(),
                any(byte[].class),
                anyString());

        byte[] data = new byte[] {1, 2, 3, 4};

        StoredObject uploaded = uploadService.upload(
                new ByteArrayInputStream(data),
                "write-intent.bin",
                "application/octet-stream",
                null);

        assertEquals(ObjectStatus.COMPLETE, uploaded.getStatus());
        assertTrue(
        deletionJobRepository.findByObjectId(uploaded.getId()).isEmpty(),
        "A successful upload must not create deletion jobs");
        assertEquals(1, uploaded.getChunkCount());
        assertEquals(2, workersSeen.size());
        assertEquals(Set.of(uploaded.getId()), objectIdsSeen);

        verify(workerClient, times(2)).putChunk(
                any(Worker.class),
                anyString(),
                any(byte[].class),
                anyString());
    }
    @Test
void lostWorkerRepliesBecomeDeletionJobsWhenUploadFails(){
    saveWorker("a-", 8082);
    saveWorker("b-", 8083);

    // Each key identifies one chunk copy on one worker.
    Map<String, byte[]> storedCopies = new HashMap<>();
    Set<String> objectIdsSeen = new HashSet<>();

    doAnswer(invocation -> {
        assertFalse(
                TransactionSynchronizationManager
                        .isActualTransactionActive(),
                "Worker PUT must happen outside a database transaction");

        Worker target = invocation.getArgument(0);
        String chunkId = invocation.getArgument(1);
        byte[] bytes = invocation.getArgument(2);

        ChunkWriteIntent intent = intentRepository
                .findByChunkIdAndWorkerId(
                        chunkId,
                        target.getWorkerId())
                .orElseThrow(() -> new AssertionError(
                        "Write intent must exist before sending the chunk"));

        objectIdsSeen.add(intent.getObjectId());

        // The simulated worker stores the bytes.
        String copyKey = target.getWorkerId() + "/" + chunkId;
        storedCopies.put(copyKey, bytes.clone());

        // Its success response never reaches the controller.
        throw new WorkerUnavailableException(
                "Simulated timeout after the worker stored the chunk");
    }).when(workerClient).putChunk(
            any(Worker.class),
            anyString(),
            any(byte[].class),
            anyString());

    // Make deletion affect the simulated storage if cleanup requests it.
    doAnswer(invocation -> {
        Worker target = invocation.getArgument(0);
        String chunkId = invocation.getArgument(1);

        storedCopies.remove(target.getWorkerId() + "/" + chunkId);
        return null;
    }).when(workerClient).deleteChunk(
            any(Worker.class),
            anyString());

    byte[] data = new byte[] {10, 20, 30, 40};

    assertThrows(
            InsufficientWorkersException.class,
            () -> uploadService.upload(
                    new ByteArrayInputStream(data),
                    "lost-responses.bin",
                    "application/octet-stream",
                    null));

    verify(workerClient, times(2)).putChunk(
            any(Worker.class),
            anyString(),
            any(byte[].class),
            anyString());

    assertEquals(1, objectIdsSeen.size());
    String failedObjectId = objectIdsSeen.iterator().next();

    // The failed upload's normal metadata has been removed.
    assertEquals(0L, objectRepository.count());
    assertEquals(0L, chunkRepository.count());
    assertEquals(0L, replicaRepository.count());

    // The possible worker copies must still be remembered.
 // Cleanup information has moved from intents into deletion jobs.
assertTrue(
        intentRepository.findByObjectId(failedObjectId).isEmpty());

List<ChunkDeletionJob> jobs =
        deletionJobRepository.findByObjectId(failedObjectId);

assertEquals(2, jobs.size());
assertEquals(2, storedCopies.size());

for (ChunkDeletionJob job : jobs) {
    String copyKey =
            job.getWorkerId() + "/" + job.getChunkId();

    assertTrue(
            storedCopies.containsKey(copyKey),
            "The deletion job must identify a stored worker copy");

    assertArrayEquals(data, storedCopies.get(copyKey));
}
}
    private void saveWorker(String prefix, int port) {
        Worker worker = new Worker(
                prefix + UUID.randomUUID(),
                "http://localhost:" + port,
                Instant.now(),
                WorkerStatus.ALIVE,
                0L,
                0);

        worker.setRegistrationToken(UUID.randomUUID().toString());
        workerRepository.save(worker);
    }
}