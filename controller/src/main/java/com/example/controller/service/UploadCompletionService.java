package com.example.controller.service;

import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.StoredObject;
import com.example.controller.exception.ObjectConflictException;
import com.example.controller.repository.StoredObjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class UploadCompletionService {

    private final StoredObjectRepository objectRepository;

    public UploadCompletionService(
            StoredObjectRepository objectRepository) {

        this.objectRepository = objectRepository;
    }

    @Transactional
    public StoredObject complete(
            String objectId,
            long totalSize,
            int chunkCount,
            String checksum) {

        StoredObject object = objectRepository
                .findByIdForUpdate(objectId)
                .orElseThrow(() -> new ObjectConflictException(
                        "Upload was removed before completion: "
                                + objectId));

        if (object.getStatus() != ObjectStatus.UPLOADING) {
            throw new ObjectConflictException(
                    "Upload is no longer in progress: " + objectId);
        }

        object.setSize(totalSize);
        object.setChunkCount(chunkCount);
        object.setChecksum(checksum);
        object.setStatus(ObjectStatus.COMPLETE);
        object.setModifiedAt(Instant.now());

        return objectRepository.save(object);
    }
}