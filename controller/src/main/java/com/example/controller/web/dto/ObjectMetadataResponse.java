package com.example.controller.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ObjectMetadataResponse(
        String id,
        String name,
        long size,
        String contentType,
        String checksum,
        int chunkCount,
        String status,
        Instant createdAt,
        Instant modifiedAt,
        Map<String, String> customMetadata,
        List<ChunkInfoResponse> chunks) {
}
