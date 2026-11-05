package com.example.common.dto;

import java.util.List;

public record RegisterRequest(String workerId, String url, List<ChunkInfo> chunks) {
}
