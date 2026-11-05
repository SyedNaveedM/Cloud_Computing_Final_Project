package com.example.controller.client;

import com.example.controller.domain.Worker;

public interface WorkerClient {

    void putChunk(Worker worker, String chunkId, byte[] data, String checksum);

    byte[] getChunk(Worker worker, String chunkId);

    /**
     * Best-effort and idempotent: implementations swallow failures rather than throwing,
     * since callers (upload rollback, object delete) treat delete as fire-and-forget.
     */
    void deleteChunk(Worker worker, String chunkId);
}
