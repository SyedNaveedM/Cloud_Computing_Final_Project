package com.example.controller.web.dto;

import java.util.List;

public record ChunkInfoResponse(int index, String chunkId, long size, String checksum, List<ReplicaInfo> replicas) {
}
