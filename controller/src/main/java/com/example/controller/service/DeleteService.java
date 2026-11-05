package com.example.controller.service;

import com.example.controller.client.WorkerClient;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.Replica;
import com.example.controller.domain.StoredObject;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.ObjectNotFoundException;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeleteService {

    private final StoredObjectRepository objectRepository;
    private final ChunkRepository chunkRepository;
    private final ReplicaRepository replicaRepository;
    private final WorkerRepository workerRepository;
    private final WorkerClient workerClient;

    public DeleteService(StoredObjectRepository objectRepository,
                          ChunkRepository chunkRepository,
                          ReplicaRepository replicaRepository,
                          WorkerRepository workerRepository,
                          WorkerClient workerClient) {
        this.objectRepository = objectRepository;
        this.chunkRepository = chunkRepository;
        this.replicaRepository = replicaRepository;
        this.workerRepository = workerRepository;
        this.workerClient = workerClient;
    }

    /**
     * Only ALIVE workers are contacted; a DEAD worker's orphaned chunks are cleaned up later
     * by Person B's registration/reconcile logic once it comes back, since the Controller no
     * longer knows about this object by the time that happens.
     */
    @Transactional
    public void deleteObject(String objectId) {
        StoredObject object = objectRepository.findById(objectId)
                .orElseThrow(() -> new ObjectNotFoundException(objectId));

        object.setStatus(ObjectStatus.DELETING);
        objectRepository.save(object);

        for (Chunk chunk : chunkRepository.findByObjectIdOrderByChunkIndex(objectId)) {
            for (Replica replica : replicaRepository.findByChunkId(chunk.getChunkId())) {
                workerRepository.findById(replica.getWorkerId())
                        .filter(worker -> worker.getStatus() == WorkerStatus.ALIVE)
                        .ifPresent(worker -> workerClient.deleteChunk(worker, chunk.getChunkId()));
            }
            replicaRepository.deleteByChunkId(chunk.getChunkId());
        }
        chunkRepository.deleteByObjectId(objectId);
        objectRepository.deleteById(objectId);
    }
}
