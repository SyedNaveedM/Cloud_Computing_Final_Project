package com.example.controller.service;

import com.example.controller.domain.*;
import com.example.controller.exception.ObjectNotFoundException;
import com.example.controller.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ObjectDeletionMetadataService {
    private final StoredObjectRepository objects;
    private final ChunkRepository chunks;
    private final ReplicaRepository replicas;
    private final WorkerRepository workers;
    private final ChunkWriteIntentRepository intents;
    private final ChunkDeletionJobRepository jobs;

    @Transactional
    public void begin(String objectId) {
        StoredObject object = objects.findByIdForUpdate(objectId)
                .orElseThrow(() -> new ObjectNotFoundException(objectId));
        if (object.getStatus() == ObjectStatus.DELETING) return;
        object.setStatus(ObjectStatus.DELETING);
        objects.save(object);
        for (ChunkWriteIntent intent : intents.findByObjectId(objectId)) {
            queue(objectId, intent.getChunkId(), intent.getWorkerId(), intent.getWorkerUrl());
        }
        for (Chunk chunk : chunks.findByObjectIdOrderByChunkIndex(objectId)) {
            for (Replica replica : replicas.findByChunkId(chunk.getChunkId())) {
                String url = workers.findById(replica.getWorkerId()).map(Worker::getUrl).orElse(null);
                queue(objectId, chunk.getChunkId(), replica.getWorkerId(), url);
            }
        }
    }

    private void queue(String objectId, String chunkId, String workerId, String url) {
        ChunkDeletionJob job = jobs.findByChunkIdAndWorkerId(chunkId, workerId).orElse(null);
        if (job != null) {
            if (job.getObjectId() != null && !Objects.equals(job.getObjectId(), objectId))
                throw new IllegalStateException("Deletion job belongs to a different object");
            job.setObjectId(objectId);
            if (job.getWorkerUrl() == null) job.setWorkerUrl(url);
        } else {
            job = new ChunkDeletionJob();
            job.setObjectId(objectId);
            job.setChunkId(chunkId);
            job.setWorkerId(workerId);
            job.setWorkerUrl(url);
            job.setCreatedAt(Instant.now());
            job.setNextAttemptAt(Instant.now());
        }
        jobs.save(job);
    }

    @Transactional
    public boolean finish(String objectId) {
        StoredObject object = objects.findByIdForUpdate(objectId).orElse(null);
        if (object == null) return true;
        if (object.getStatus() != ObjectStatus.DELETING || !jobs.findByObjectId(objectId).isEmpty()) return false;
        for (Chunk chunk : chunks.findByObjectIdOrderByChunkIndex(objectId)) replicas.deleteByChunkId(chunk.getChunkId());
        chunks.deleteByObjectId(objectId);
        intents.deleteAll(intents.findByObjectId(objectId));
        objects.delete(object);
        return true;
    }
}
