package com.example.controller.service;

import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.StoredObject;
import com.example.controller.exception.ObjectConflictException;
import com.example.controller.exception.ObjectNotFoundException;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.web.dto.ChunkInfoResponse;
import com.example.controller.web.dto.ObjectMetadataResponse;
import com.example.controller.web.dto.ReplicaInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class MetadataService {

    private final StoredObjectRepository objectRepository;
    private final ChunkRepository chunkRepository;
    private final ReplicaRepository replicaRepository;

    public MetadataService(StoredObjectRepository objectRepository,
                            ChunkRepository chunkRepository,
                            ReplicaRepository replicaRepository) {
        this.objectRepository = objectRepository;
        this.chunkRepository = chunkRepository;
        this.replicaRepository = replicaRepository;
    }

    public StoredObject getObjectOrThrow(String objectId) {
        return objectRepository.findById(objectId).orElseThrow(() -> new ObjectNotFoundException(objectId));
    }

    public ObjectMetadataResponse getMetadata(String objectId) {
        return toResponse(getObjectOrThrow(objectId));
    }

    /**
     * Merges {@code patch} into the object's customMetadata: a null value removes that key,
     * any other value sets it. No chunk data is touched, only modifiedAt and the metadata map.
     */
    @Transactional
    public ObjectMetadataResponse patchMetadata(String objectId, Map<String, String> patch) {
        StoredObject object = objectRepository.findByIdForUpdate(objectId)
                .orElseThrow(() -> new ObjectNotFoundException(objectId));
        if (object.getStatus() == ObjectStatus.DELETING) {
            throw new ObjectConflictException("Object " + objectId + " is being deleted");
        }

        Map<String, String> metadata = object.getCustomMetadata();
        for (Map.Entry<String, String> entry : patch.entrySet()) {
            if (entry.getValue() == null) {
                metadata.remove(entry.getKey());
            } else {
                metadata.put(entry.getKey(), entry.getValue());
            }
        }
        object.setModifiedAt(Instant.now());
        return toResponse(objectRepository.save(object));
    }

    private ObjectMetadataResponse toResponse(StoredObject object) {
        List<ChunkInfoResponse> chunks = chunkRepository.findByObjectIdOrderByChunkIndex(object.getId()).stream()
                .map(chunk -> new ChunkInfoResponse(
                        chunk.getChunkIndex(),
                        chunk.getChunkId(),
                        chunk.getSize(),
                        chunk.getChecksum(),
                        replicaRepository.findByChunkId(chunk.getChunkId()).stream()
                                .map(replica -> new ReplicaInfo(replica.getWorkerId(), replica.getState().name()))
                                .toList()))
                .toList();

        return new ObjectMetadataResponse(
                object.getId(),
                object.getName(),
                object.getSize(),
                object.getContentType(),
                object.getChecksum(),
                object.getChunkCount(),
                object.getStatus().name(),
                object.getCreatedAt(),
                object.getModifiedAt(),
                object.getCustomMetadata(),
                chunks);
    }
}
