package com.example.controller.service;

import com.example.controller.client.WorkerClient;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.ChunkDeletionJob;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.ObjectConflictException;
import com.example.controller.repository.ChunkDeletionJobRepository;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ChunkWriteIntentRepository;
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
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:upload-completion-race-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UploadCompletionRaceTest {

    @Autowired
    private UploadService uploadService;

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
    private ChunkDeletionJobRepository jobRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @MockitoBean(enforceOverride = true)
    private WorkerClient workerClient;

    @Test
    void cleanupBeforeCompletionPreventsMetadataRecreation() {
        assertFalse(
                TransactionSynchronizationManager
                        .isActualTransactionActive());

        saveWorker("completion-worker-1", 8082);
        saveWorker("completion-worker-2", 8083);

        AtomicReference<Chunk> capturedChunk = new AtomicReference<>();

        byte[] data = new byte[] {10, 20, 30, 40};

        ByteArrayInputStream input = new ByteArrayInputStream(data) {
            @Override
            public byte[] readNBytes(int length) throws IOException {
                byte[] bytes = super.readNBytes(length);

                // The empty read occurs after the last chunk was committed.
                if (bytes.length == 0 && capturedChunk.get() == null) {
                    assertFalse(
                            TransactionSynchronizationManager
                                    .isActualTransactionActive());

                    assertEquals(1L, chunkRepository.count());

                    Chunk chunk = chunkRepository.findAll().getFirst();
                    capturedChunk.set(chunk);

                    assertEquals(
                            ObjectStatus.UPLOADING,
                            objectRepository
                                    .findById(chunk.getObjectId())
                                    .orElseThrow()
                                    .getStatus());

                    assertEquals(
                            2,
                            replicaRepository
                                    .findByChunkId(chunk.getChunkId())
                                    .size());

                    assertEquals(
                            2,
                            intentRepository
                                    .findByObjectId(chunk.getObjectId())
                                    .size());

                    // Commit cleanup before upload completion can run.
                    cleanupService.removeIncompleteUpload(
                            chunk.getObjectId());

                    assertNoUploadMetadata();

                    assertEquals(
                            2,
                            jobRepository
                                    .findByObjectId(chunk.getObjectId())
                                    .size());
                }

                return bytes;
            }
        };

        // Mocked worker PUT calls return normally.
        // Completion must fail because cleanup removed the object.
        assertThrows(
                ObjectConflictException.class,
                () -> uploadService.upload(
                        input,
                        "completion-race.bin",
                        "application/octet-stream",
                        null));

        Chunk removedChunk = capturedChunk.get();

        assertNotNull(
                removedChunk,
                "The test must reach cleanup after chunk commit");

        assertFalse(
                TransactionSynchronizationManager
                        .isActualTransactionActive());

        // Completion and its failure handling must not recreate metadata.
        assertNoUploadMetadata();

        var remainingJobs = jobRepository
                .findByObjectId(removedChunk.getObjectId());

        assertEquals(2, remainingJobs.size());
        assertEquals(2L, jobRepository.count());

        assertEquals(
                Set.of(
                        "completion-worker-1",
                        "completion-worker-2"),
                remainingJobs.stream()
                        .map(ChunkDeletionJob::getWorkerId)
                        .collect(Collectors.toSet()));

        for (ChunkDeletionJob job : remainingJobs) {
            assertEquals(
                    removedChunk.getChunkId(),
                    job.getChunkId());

            assertEquals(
                    workerRepository
                            .findById(job.getWorkerId())
                            .orElseThrow()
                            .getUrl(),
                    job.getWorkerUrl());

            assertEquals(0, job.getAttemptCount());
        }

        verify(workerClient, times(2)).putChunk(
                any(Worker.class),
                eq(removedChunk.getChunkId()),
                any(byte[].class),
                anyString());
    }

    private void assertNoUploadMetadata() {
        assertEquals(0L, objectRepository.count());
        assertEquals(0L, chunkRepository.count());
        assertEquals(0L, replicaRepository.count());
        assertEquals(0L, intentRepository.count());
    }

    private void saveWorker(String workerId, int port) {
        Worker worker = new Worker(
                workerId,
                "http://localhost:" + port,
                Instant.now(),
                WorkerStatus.ALIVE,
                0L,
                0);

        worker.setRegistrationToken(UUID.randomUUID().toString());
        workerRepository.saveAndFlush(worker);
    }
}