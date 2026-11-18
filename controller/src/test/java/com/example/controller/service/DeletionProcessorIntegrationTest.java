package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.controller.client.*;
import com.example.controller.domain.*;
import com.example.controller.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:deletion-processor-integration;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "objectstore.maintenance.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class DeletionProcessorIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectDeletionMetadataService deletion;
    @Autowired ChunkDeletionProcessor processor;
    @Autowired ChunkDeletionJobService service;
    @Autowired ChunkDeletionJobRepository jobs;
    @Autowired WorkerRepository workers;
    @Autowired StoredObjectRepository objects;
    @Autowired ChunkRepository chunks;
    @Autowired ReplicaRepository replicas;
    @Autowired ChunkWriteIntentRepository intents;
    @Autowired DownloadService download;
    @MockitoBean(enforceOverride = true) WorkerClient client;
    private Worker worker;
    private ChunkDeletionJob job;

    @BeforeEach
    void setUp() {
        jobs.deleteAll(); intents.deleteAll(); replicas.deleteAll(); chunks.deleteAll(); objects.deleteAll(); workers.deleteAll();
        worker = new Worker("worker1", "http://localhost:8082", Instant.now(), WorkerStatus.ALIVE, 0, 0);
        worker.setRegistrationToken(UUID.randomUUID().toString());
        workers.save(worker);
        job = new ChunkDeletionJob();
        job.setObjectId(UUID.randomUUID().toString()); job.setChunkId(UUID.randomUUID().toString());
        job.setWorkerId(worker.getWorkerId()); job.setWorkerUrl(worker.getUrl());
        job.setCreatedAt(Instant.now()); job.setNextAttemptAt(Instant.now().minusSeconds(1));
        job = jobs.saveAndFlush(job);
    }

    private ChunkDeletionJobService.PendingDeletion pending() {
        return service.findDueJobs(Instant.now()).getFirst();
    }

    private String completeObjectWithTwoReplicas() {
        jobs.deleteAll();
        StoredObject object = new StoredObject();
        object.setId(job.getObjectId()); object.setStatus(ObjectStatus.COMPLETE);
        objects.saveAndFlush(object);
        chunks.saveAndFlush(new Chunk(job.getChunkId(), object.getId(), 0, 3, ChecksumUtil.sha256(new byte[]{1, 2, 3})));
        Worker second = new Worker("worker2", "http://localhost:8083", Instant.now(), WorkerStatus.ALIVE, 0, 0);
        second.setRegistrationToken(UUID.randomUUID().toString()); workers.saveAndFlush(second);
        replicas.saveAndFlush(new Replica(job.getChunkId(), worker.getWorkerId(), ReplicaState.OK));
        replicas.saveAndFlush(new Replica(job.getChunkId(), second.getWorkerId(), ReplicaState.OK));
        return object.getId();
    }

    private void makeObjectJobsDue(String objectId) {
        for (ChunkDeletionJob pending : jobs.findByObjectId(objectId)) {
            pending.setNextAttemptAt(Instant.now().minusSeconds(1));
            jobs.saveAndFlush(pending);
        }
    }

    @Test
    void repeatedPendingDeletePreservesProgressAndReturns204OnlyAfterConfirmation() throws Exception {
        String objectId = completeObjectWithTwoReplicas();
        Worker offline = workers.findById("worker2").orElseThrow();
        offline.setStatus(WorkerStatus.DEAD); workers.saveAndFlush(offline);

        http.perform(delete("/api/v1/objects/{id}", objectId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("physical deletion is pending")));
        http.perform(get("/api/v1/objects/{id}", objectId)).andExpect(status().isNotFound());
        http.perform(get("/api/v1/objects/{id}/metadata", objectId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETING"));
        assertEquals(1, jobs.findByObjectId(objectId).size());
        ChunkDeletionJob outstanding = jobs.findByObjectId(objectId).getFirst();
        assertEquals("worker2", outstanding.getWorkerId());

        // Keep the backoff deterministic, independent of how long the test runner pauses.
        outstanding.setNextAttemptAt(Instant.now().plusSeconds(300));
        outstanding = jobs.saveAndFlush(outstanding);
        outstanding = jobs.findById(outstanding.getId()).orElseThrow();
        http.perform(delete("/api/v1/objects/{id}", objectId)).andExpect(status().isServiceUnavailable());
        var afterRetry = jobs.findByObjectId(objectId);
        assertEquals(1, afterRetry.size());
        assertEquals(outstanding.getId(), afterRetry.getFirst().getId());
        assertEquals(outstanding.getVersion(), afterRetry.getFirst().getVersion());
        assertEquals(outstanding.getAttemptCount(), afterRetry.getFirst().getAttemptCount());
        assertEquals(outstanding.getNextAttemptAt(), afterRetry.getFirst().getNextAttemptAt());
        verify(client, times(1)).deleteChunkConfirmed(any(), eq(job.getChunkId()));

        offline.setStatus(WorkerStatus.ALIVE);
        offline.setRegistrationToken(UUID.randomUUID().toString()); workers.saveAndFlush(offline);
        makeObjectJobsDue(objectId);
        http.perform(delete("/api/v1/objects/{id}", objectId)).andExpect(status().isNoContent());
        assertFalse(objects.existsById(objectId));
        assertFalse(chunks.existsById(job.getChunkId()));
        assertTrue(replicas.findByChunkId(job.getChunkId()).isEmpty());
        assertTrue(jobs.findByObjectId(objectId).isEmpty());
        verify(client, times(2)).deleteChunkConfirmed(any(), eq(job.getChunkId()));
        http.perform(delete("/api/v1/objects/{id}", objectId)).andExpect(status().isNotFound());
    }

    @Test
    void unrelatedJobBacklogDoesNotPreventThisObjectFromCompleting() throws Exception {
        String objectId = completeObjectWithTwoReplicas();
        for (int i = 0; i < 100; i++) {
            ChunkDeletionJob other = new ChunkDeletionJob();
            other.setObjectId(UUID.randomUUID().toString()); other.setChunkId(UUID.randomUUID().toString());
            other.setWorkerId("unrelated-worker"); other.setWorkerUrl("http://unrelated-worker");
            other.setCreatedAt(Instant.now().minusSeconds(60));
            other.setNextAttemptAt(Instant.now().minusSeconds(60));
            jobs.saveAndFlush(other);
        }
        http.perform(delete("/api/v1/objects/{id}", objectId)).andExpect(status().isNoContent());
        assertFalse(objects.existsById(objectId));
        assertEquals(100, jobs.count());
        verify(client, times(2)).deleteChunkConfirmed(any(), eq(job.getChunkId()));
    }

    @Test
    void lostWorkerResponseReturns503UntilARetryConfirmsDeletion() throws Exception {
        String objectId = completeObjectWithTwoReplicas();
        doThrow(new WorkerUnavailableException("Response lost after worker removed bytes"))
                .doNothing().when(client).deleteChunkConfirmed(any(), eq(job.getChunkId()));
        http.perform(delete("/api/v1/objects/{id}", objectId)).andExpect(status().isServiceUnavailable());
        assertEquals(ObjectStatus.DELETING, objects.findById(objectId).orElseThrow().getStatus());
        assertEquals(1, jobs.findByObjectId(objectId).size());
        makeObjectJobsDue(objectId);
        http.perform(delete("/api/v1/objects/{id}", objectId)).andExpect(status().isNoContent());
        assertFalse(objects.existsById(objectId));
        verify(client, times(3)).deleteChunkConfirmed(any(), eq(job.getChunkId()));
    }

    @Test
    void retryFinishesMetadataWhenAllWorkerAcknowledgementsAlreadyCommitted() throws Exception {
        String objectId = completeObjectWithTwoReplicas();
        deletion.begin(objectId);
        for (var pending : service.findDueJobsForObject(objectId, Instant.now())) {
            assertEquals(ChunkDeletionProcessor.Result.COMPLETED, processor.process(pending));
        }
        // Recreate the durable state left if execution stops before finish().
        assertEquals(ObjectStatus.DELETING, objects.findById(objectId).orElseThrow().getStatus());
        assertTrue(jobs.findByObjectId(objectId).isEmpty());
        http.perform(delete("/api/v1/objects/{id}", objectId)).andExpect(status().isNoContent());
        assertFalse(objects.existsById(objectId));
        verify(client, times(2)).deleteChunkConfirmed(any(), eq(job.getChunkId()));
    }

    @Test
    void confirmedDeleteRunsOutsideTransactionAndRemovesTheJob() {
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(jobs.existsById(job.getId()));
            return null;
        }).when(client).deleteChunkConfirmed(any(), anyString());
        assertEquals(ChunkDeletionProcessor.Result.COMPLETED, processor.process(pending()));
        assertFalse(jobs.existsById(job.getId()));
    }

    @Test
    void lostResponseKeepsJobAndRepeatedDeleteCanCompleteIt() {
        doThrow(new WorkerUnavailableException("Response lost after deletion"))
                .doNothing().when(client).deleteChunkConfirmed(any(), anyString());
        assertEquals(ChunkDeletionProcessor.Result.RETRY_SCHEDULED, processor.process(pending()));
        ChunkDeletionJob stored = jobs.findById(job.getId()).orElseThrow();
        assertEquals(1, stored.getAttemptCount());
        assertNotNull(stored.getLastError());
        stored.setNextAttemptAt(Instant.now().minusSeconds(1)); jobs.saveAndFlush(stored);
        assertEquals(ChunkDeletionProcessor.Result.COMPLETED, processor.process(pending()));
        verify(client, times(2)).deleteChunkConfirmed(any(), eq(job.getChunkId()));
    }

    @Test
    void registrationChangeDuringDeletePreventsAcknowledgement() {
        doAnswer(call -> {
            Worker newer = workers.findById(worker.getWorkerId()).orElseThrow();
            newer.setRegistrationToken(UUID.randomUUID().toString()); workers.saveAndFlush(newer);
            return null;
        }).when(client).deleteChunkConfirmed(any(), anyString());
        assertEquals(ChunkDeletionProcessor.Result.RETRY_SCHEDULED, processor.process(pending()));
        assertTrue(jobs.existsById(job.getId()));
    }

    @Test
    void liveObjectBlocksDeletion() {
        StoredObject object = new StoredObject(); object.setId(job.getObjectId()); object.setStatus(ObjectStatus.COMPLETE);
        objects.saveAndFlush(object);
        assertEquals(ChunkDeletionProcessor.Result.RETRY_SCHEDULED, processor.process(pending()));
        verifyNoInteractions(client);
    }

    @Test
    void staleSnapshotDoesNotSendAnotherRequest() {
        var old = pending();
        assertTrue(service.recordFailure(old, Instant.now(), "Earlier failure"));
        assertEquals(ChunkDeletionProcessor.Result.SKIPPED, processor.process(old));
        verifyNoInteractions(client);
    }

    @Test
    void changedWorkerAddressDoesNotRedirectCleanup() {
        worker.setUrl("http://localhost:9999"); workers.saveAndFlush(worker);
        assertEquals(ChunkDeletionProcessor.Result.RETRY_SCHEDULED, processor.process(pending()));
        verifyNoInteractions(client);
    }

    @Test
    void missingReplicaFallsBackToTheOtherCopy() {
        jobs.deleteAll();
        StoredObject object = new StoredObject(); object.setId(UUID.randomUUID().toString()); object.setStatus(ObjectStatus.COMPLETE);
        objects.saveAndFlush(object);
        byte[] bytes = {1, 2, 3};
        Chunk chunk = chunks.saveAndFlush(new Chunk(UUID.randomUUID().toString(), object.getId(), 0, bytes.length, ChecksumUtil.sha256(bytes)));
        Worker second = new Worker("worker2", "http://localhost:8083", Instant.now(), WorkerStatus.ALIVE, 0, 0);
        second.setRegistrationToken(UUID.randomUUID().toString()); workers.saveAndFlush(second);
        replicas.saveAndFlush(new Replica(chunk.getChunkId(), worker.getWorkerId(), ReplicaState.OK));
        replicas.saveAndFlush(new Replica(chunk.getChunkId(), second.getWorkerId(), ReplicaState.OK));
        AtomicInteger calls = new AtomicInteger();
        // Force the first selected location to be missing, regardless of shuffle order.
        when(client.getChunk(any(), eq(chunk.getChunkId()))).thenAnswer(call -> {
            if (calls.getAndIncrement() == 0) throw new ChunkNotFoundException("Missing copy");
            return bytes;
        });
        assertArrayEquals(bytes, download.fetchVerified(chunk));
        assertEquals(2, calls.get());
        assertEquals(1, replicas.findByChunkId(chunk.getChunkId()).size());
    }
}
