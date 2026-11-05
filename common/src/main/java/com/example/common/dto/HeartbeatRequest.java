package com.example.common.dto;

public record HeartbeatRequest(String workerId, long usedBytes, int chunkCount) {
}
