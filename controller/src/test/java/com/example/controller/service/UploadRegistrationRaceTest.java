package com.example.controller.service;

import com.example.common.Constants;
import com.example.common.dto.RegisterRequest;
import com.example.controller.client.WorkerClient;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.InsufficientWorkersException;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:upload-registration-race-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UploadRegistrationRaceTest {

    @Autowired
    private UploadService uploadService;

    @Autowired
    private RegistrationMetadataService registrationService;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private StoredObjectRepository objectRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ReplicaRepository replicaRepository;

    @MockitoBean(enforceOverride = true)
    private WorkerClient workerClient;

    @Test
    void registrationDuringSecondChunkAbortsAndCleansTheUpload() {
        saveWorker("a-", 8082);
        saveWorker("b-", 8083);

        AtomicInteger putCalls = new AtomicInteger();
        AtomicReference<String> firstChunkId = new AtomicReference<>();
        AtomicReference<String> returningWorkerId = new AtomicReference<>();

        doAnswer(invocation -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive(),
                    "Worker PUT must happen outside a database transaction");

            int callNumber = putCalls.incrementAndGet();

            // Calls 1 and 2 write the first chunk's two copies.
            // Calls 3 and 4 write the second chunk's two copies.
            if (callNumber == 4) {
                assertEquals(1L, chunkRepository.count());
                assertEquals(2L, replicaRepository.count());

                Chunk firstChunk = chunkRepository.findAll().get(0);
                firstChunkId.set(firstChunk.getChunkId());

                assertEquals(
                        ObjectStatus.UPLOADING,
                        objectRepository.findById(firstChunk.getObjectId())
                                .orElseThrow()
                                .getStatus());

                Worker target = invocation.getArgument(0);
                returningWorkerId.set(target.getWorkerId());

                // Start a real registration transaction while the
                // upload is waiting for this simulated PUT to return.
                var attempt = registrationService.begin(
                        new RegisterRequest(
                                target.getWorkerId(),
                                target.getUrl(),
                                List.of()));

                assertNotEquals(
                        target.getRegistrationToken(),
                        attempt.token());
            }

            // Simulate the worker reporting a successful PUT.
            return null;
        }).when(workerClient).putChunk(
                any(Worker.class),
                anyString(),
                any(byte[].class),
                anyString());

        doAnswer(invocation -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive(),
                    "Worker DELETE must happen outside a database transaction");

            // Metadata cleanup must commit before worker deletion starts.
            assertNoUploadMetadata();
            return null;
        }).when(workerClient).deleteChunk(
                any(Worker.class),
                anyString());

        // This produces two chunks: one full chunk and one extra byte.
        byte[] data = new byte[Constants.CHUNK_SIZE + 1];

        assertThrows(
                InsufficientWorkersException.class,
                () -> uploadService.upload(
                        new ByteArrayInputStream(data),
                        "registration-race.bin",
                        "application/octet-stream",
                        null));

        assertEquals(4, putCalls.get());
        assertNoUploadMetadata();

        // Cleanup attempts deletion of the first chunk's recorded copies.
        verify(workerClient, times(2)).deleteChunk(
                any(Worker.class),
                eq(firstChunkId.get()));

        // Failed upload cleanup must not undo the newer registration.
        assertEquals(
                WorkerStatus.REGISTERING,
                workerRepository.findById(returningWorkerId.get())
                        .orElseThrow()
                        .getStatus());
    }

    private void assertNoUploadMetadata() {
        assertEquals(0L, objectRepository.count());
        assertEquals(0L, chunkRepository.count());
        assertEquals(0L, replicaRepository.count());
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