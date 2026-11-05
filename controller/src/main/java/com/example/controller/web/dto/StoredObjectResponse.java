package com.example.controller.web.dto;

import com.example.controller.domain.StoredObject;

import java.time.Instant;
import java.util.Map;

public record StoredObjectResponse(
        String id,
        String name,
        long size,
        String contentType,
        String checksum,
        int chunkCount,
        String status,
        Instant createdAt,
        Instant modifiedAt,
        Map<String, String> customMetadata) {

    public static StoredObjectResponse from(StoredObject object) {
        return new StoredObjectResponse(
                object.getId(),
                object.getName(),
                object.getSize(),
                object.getContentType(),
                object.getChecksum(),
                object.getChunkCount(),
                object.getStatus().name(),
                object.getCreatedAt(),
                object.getModifiedAt(),
                object.getCustomMetadata());
    }
}
