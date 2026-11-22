package com.example.controller.client;

import com.example.controller.domain.Worker;
import com.example.common.dto.ChunkInfo;
import java.util.List;

public interface WorkerClient {

    void putChunk(Worker worker, String chunkId, byte[] data, String checksum);

    byte[] getChunk(Worker worker, String chunkId);

    /**
     * Best-effort and idempotent: implementations swallow failures rather than throwing,
     * since callers (upload rollback, object delete) treat delete as fire-and-forget.
     */
    void deleteChunk(Worker worker, String chunkId);
    /**
 * Returns normally only when the worker acknowledges completed deletion.
 * Throws WorkerUnavailableException when deletion cannot be confirmed.
 */
void deleteChunkConfirmed(Worker worker, String chunkId);
    List<ChunkInfo> listChunks(Worker worker);

    default void evictChunk(Worker worker, String chunkId) {
        throw new UnsupportedOperationException("Replica eviction is not implemented");
    }
}
