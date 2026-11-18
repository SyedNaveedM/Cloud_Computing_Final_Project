package com.example.controller.service;

import com.example.controller.domain.ChunkWriteIntent;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.StoredObject;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.repository.ChunkWriteIntentRepository;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import com.example.controller.service.UploadReplicaCommitService.UploadTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class ChunkWriteIntentService {

    private final WorkerRepository workerRepository;
    private final StoredObjectRepository objectRepository;
    private final ChunkWriteIntentRepository intentRepository;

    public ChunkWriteIntentService(
            WorkerRepository workerRepository,
            StoredObjectRepository objectRepository,
            ChunkWriteIntentRepository intentRepository) {

        this.workerRepository = workerRepository;
        this.objectRepository = objectRepository;
        this.intentRepository = intentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryRecord(
            String objectId,
            String chunkId,
            UploadTarget target) {

        Objects.requireNonNull(objectId, "Object ID is required");
        Objects.requireNonNull(chunkId, "Chunk ID is required");
        Objects.requireNonNull(target, "Upload target is required");

        // Match the lock order used by replica commit and registration.
        Worker current = workerRepository
                .findByIdForUpdate(target.workerId())
                .orElse(null);

        if (current == null
                || current.getStatus() != WorkerStatus.ALIVE
                || target.registrationToken() == null
                || target.registrationToken().isBlank()
                || !Objects.equals(
                        target.registrationToken(),
                        current.getRegistrationToken())
                || !Objects.equals(target.url(), current.getUrl())) {

            return false;
        }

        StoredObject object = objectRepository
                .findByIdForUpdate(objectId)
                .orElse(null);

        if (object == null
                || object.getStatus() != ObjectStatus.UPLOADING) {

            return false;
        }

        ChunkWriteIntent existing = intentRepository
                .findByChunkIdAndWorkerId(chunkId, target.workerId())
                .orElse(null);

        if (existing != null) {
            if (!Objects.equals(existing.getObjectId(), objectId)
                    || !Objects.equals(
                            existing.getWorkerUrl(), target.url())
                    || !Objects.equals(
                            existing.getRegistrationToken(),
                            target.registrationToken())) {

                throw new IllegalStateException(
                        "Write intent belongs to a different upload "
                        + "or worker registration");
            }

            return true;
        }

        ChunkWriteIntent intent = new ChunkWriteIntent();
        intent.setObjectId(objectId);
        intent.setChunkId(chunkId);
        intent.setWorkerId(target.workerId());
        intent.setWorkerUrl(target.url());
        intent.setRegistrationToken(target.registrationToken());
        intent.setCreatedAt(Instant.now());

        intentRepository.save(intent);
        return true;
    }
}