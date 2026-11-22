package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.common.dto.*;
import com.example.controller.client.*;
import com.example.controller.domain.*;
import com.example.controller.exception.*;
import com.example.controller.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cluster-recovery-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "objectstore.maintenance.enabled=false"
})
@ActiveProfiles("fake")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ClusterRecoveryTest {
    @Autowired UploadService upload;
    @Autowired DownloadService download;
    @Autowired DeleteService delete;
    @Autowired ObjectDeletionMetadataService deletionMetadata;
    @Autowired WorkerHeartbeatService heartbeat;
    @Autowired RegistrationCoordinator registration;
    @Autowired ReplicationManager repair;
    @Autowired ChunkDeletionJobService deletionJobs;
    @Autowired ChunkDeletionProcessor processor;
    @Autowired WorkerRepository workers;
    @Autowired StoredObjectRepository objects;
    @Autowired ChunkRepository chunks;
    @Autowired ReplicaRepository replicas;
    @Autowired ChunkWriteIntentRepository intents;
    @Autowired ChunkDeletionJobRepository jobs;
    @Autowired InMemoryWorkerClient client;
    private final byte[] data = new byte[]{10, 20, 30, 40};

    @BeforeEach
    void reset() {
        jobs.deleteAll(); intents.deleteAll(); replicas.deleteAll(); chunks.deleteAll(); objects.deleteAll(); workers.deleteAll();
        for (int i = 1; i <= 4; i++) {
            Worker worker = new Worker("worker" + i, "http://fake-worker" + i, Instant.now(), WorkerStatus.ALIVE, 0, 0);
            worker.setRegistrationToken(UUID.randomUUID().toString());
            workers.save(worker);
            client.reviveWorker(worker.getWorkerId());
        }
    }

    private StoredObject object() {
        return upload.upload(new ByteArrayInputStream(data), "recovery.bin", "application/octet-stream", null);
    }

    private Chunk chunk(StoredObject object) { return chunks.findByObjectIdOrderByChunkIndex(object.getId()).getFirst(); }

    private long healthy(Chunk chunk) {
        return replicas.findByChunkId(chunk.getChunkId()).stream()
                .filter(r -> r.getState() == ReplicaState.OK)
                .filter(r -> workers.findById(r.getWorkerId()).orElseThrow().getStatus() == WorkerStatus.ALIVE).count();
    }

    private Worker stopOne(Chunk chunk) {
        Worker worker = workers.findById(replicas.findByChunkId(chunk.getChunkId()).getFirst().getWorkerId()).orElseThrow();
        client.killWorker(worker.getWorkerId());
        worker.setLastHeartbeat(Instant.now().minusSeconds(60));
        workers.save(worker);
        assertEquals(1, heartbeat.expireBefore(Instant.now().minusSeconds(20)));
        return workers.findById(worker.getWorkerId()).orElseThrow();
    }

    @Test
    void heartbeatCannotReviveADeadWorkerOrUseAnOldToken() {
        Worker worker = workers.findById("worker1").orElseThrow();
        HeartbeatRequest request = new HeartbeatRequest(worker.getWorkerId(), 0, 0);
        assertThrows(ObjectConflictException.class, () -> heartbeat.heartbeat(request, "old-token"));
        heartbeat.heartbeat(request, worker.getRegistrationToken());
        worker = workers.findById("worker1").orElseThrow();
        worker.setStatus(WorkerStatus.DEAD); workers.save(worker);
        String old = worker.getRegistrationToken();
        assertThrows(ObjectConflictException.class, () -> heartbeat.heartbeat(request, old));
        var reply = registration.registerWithToken(new RegisterRequest("worker1", worker.getUrl(), List.of()));
        assertEquals(RegistrationCompletionService.CompletionResult.COMPLETED, reply.result());
        assertNotEquals(old, reply.token());
        heartbeat.heartbeat(request, reply.token());
    }

    @Test
    void stoppedWorkerDoesNotPreventDownloadAndRepairRestoresTwoCopies() {
        Chunk chunk = chunk(object());
        Worker stopped = stopOne(chunk);
        assertArrayEquals(data, download.fetchVerified(chunk));
        repair.runOnce();
        assertEquals(2, healthy(chunk));
        for (Replica replica : replicas.findByChunkId(chunk.getChunkId())) {
            if (replica.getState() == ReplicaState.OK && !replica.getWorkerId().equals(stopped.getWorkerId()))
                assertArrayEquals(data, client.getChunk(workers.findById(replica.getWorkerId()).orElseThrow(), chunk.getChunkId()));
        }
    }

    @Test
    void corruptionIsDetectedAndRepairedFromTheOtherCopy() {
        Chunk chunk = chunk(object());
        String damaged = replicas.findByChunkId(chunk.getChunkId()).getFirst().getWorkerId();
        client.corruptChunk(damaged, chunk.getChunkId());
        repair.runOnce();
        assertEquals(2, healthy(chunk));
        assertArrayEquals(data, client.getChunk(workers.findById(damaged).orElseThrow(), chunk.getChunkId()));
        assertArrayEquals(data, download.fetchVerified(chunk));
    }

    @Test
    void restartedWorkerIsReconciledAndExcessReplicaIsEvicted() {
        Chunk chunk = chunk(object());
        Worker stopped = stopOne(chunk);
        repair.runOnce();
        client.reviveWorker(stopped.getWorkerId());
        var reply = registration.registerWithToken(new RegisterRequest(stopped.getWorkerId(), stopped.getUrl(), client.listChunks(stopped)));
        assertEquals(RegistrationCompletionService.CompletionResult.COMPLETED, reply.result());
        assertEquals(3, healthy(chunk));
        repair.runOnce();
        assertEquals(2, healthy(chunk));
        assertEquals(2, workers.findAll().stream().filter(w -> client.hasChunk(w.getWorkerId(), chunk.getChunkId())).count());
    }

    @Test
    void deletionWaitsForOfflineWorkerAndCompletesAfterRegistration() {
        StoredObject object = object();
        Chunk chunk = chunk(object);
        Worker stopped = stopOne(chunk);
        assertThrows(ObjectUnavailableException.class, () -> delete.deleteObject(object.getId()));
        assertEquals(ObjectStatus.DELETING, objects.findById(object.getId()).orElseThrow().getStatus());
        assertThrows(ObjectNotFoundException.class, () -> download.getCompleteObjectOrThrow(object.getId()));
        assertTrue(client.hasChunk(stopped.getWorkerId(), chunk.getChunkId()));
        assertFalse(jobs.findByObjectId(object.getId()).isEmpty());

        client.reviveWorker(stopped.getWorkerId());
        var reply = registration.registerWithToken(new RegisterRequest(stopped.getWorkerId(), stopped.getUrl(), client.listChunks(stopped)));
        assertEquals(RegistrationCompletionService.CompletionResult.COMPLETED, reply.result());
        // Advance the retry schedule explicitly; this is not a real clock wait.
        for (ChunkDeletionJob job : jobs.findByObjectId(object.getId())) {
            job.setNextAttemptAt(Instant.now().minusSeconds(1)); jobs.save(job);
        }
        for (var job : deletionJobs.findDueJobs(Instant.now())) processor.process(job);
        assertTrue(deletionMetadata.finish(object.getId()));
        assertFalse(objects.existsById(object.getId()));
        for (Worker worker : workers.findAll()) assertFalse(client.hasChunk(worker.getWorkerId(), chunk.getChunkId()));
        assertThrows(ChunkRejectedException.class,
                () -> client.putChunk(stopped, chunk.getChunkId(), data, ChecksumUtil.sha256(data)));
    }

    @Test
    void controllerRestartRequiresFreshWorkerRegistration() {
        heartbeat.requireRegistrationAfterControllerRestart();
        assertTrue(workers.findByStatus(WorkerStatus.ALIVE).isEmpty());
        Worker worker = workers.findById("worker1").orElseThrow();
        var reply = registration.registerWithToken(new RegisterRequest("worker1", worker.getUrl(), List.of()));
        assertEquals(RegistrationCompletionService.CompletionResult.COMPLETED, reply.result());
        assertEquals(WorkerStatus.ALIVE, workers.findById("worker1").orElseThrow().getStatus());
    }

    @Test
    void losingEveryValidCopyDoesNotFabricateARepair() {
        Chunk chunk = chunk(object());
        for (Replica replica : replicas.findByChunkId(chunk.getChunkId()))
            client.corruptChunk(replica.getWorkerId(), chunk.getChunkId());
        repair.runOnce();
        assertEquals(0, healthy(chunk));
        assertThrows(ChunkUnavailableException.class, () -> download.fetchVerified(chunk));
    }
}
