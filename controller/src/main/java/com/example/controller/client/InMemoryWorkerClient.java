package com.example.controller.client;

import com.example.common.ChecksumUtil;
import com.example.controller.domain.Worker;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backs WorkerClient under the "fake" profile so the controller's upload/download/metadata/
 * delete flows can be exercised end to end with no Docker and no real worker nodes.
 */
@Component
@Profile("fake")
public class InMemoryWorkerClient implements WorkerClient {

    private final Map<String, Map<String, byte[]>> chunksByWorker = new ConcurrentHashMap<>();
    private final Set<String> killedWorkerIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> corruptChunksByWorker = new ConcurrentHashMap<>();

    @Override
    public void putChunk(Worker worker, String chunkId, byte[] data, String checksum) {
        assertReachable(worker);
        if (!ChecksumUtil.sha256(data).equals(checksum)) {
            throw new ChunkRejectedException("Checksum mismatch for chunk " + chunkId + " on worker " + worker.getWorkerId());
        }
        chunksByWorker.computeIfAbsent(worker.getWorkerId(), id -> new ConcurrentHashMap<>()).put(chunkId, data);
    }

    @Override
    public byte[] getChunk(Worker worker, String chunkId) {
        assertReachable(worker);
        Map<String, byte[]> store = chunksByWorker.get(worker.getWorkerId());
        byte[] data = store == null ? null : store.get(chunkId);
        if (data == null) {
            throw new ChunkNotFoundException("Chunk " + chunkId + " not found on worker " + worker.getWorkerId());
        }
        Set<String> corrupted = corruptChunksByWorker.get(worker.getWorkerId());
        if (corrupted != null && corrupted.contains(chunkId)) {
            throw new CorruptChunkException("Chunk " + chunkId + " is corrupt on worker " + worker.getWorkerId());
        }
        return data;
    }

    @Override
    public void deleteChunk(Worker worker, String chunkId) {
        Map<String, byte[]> store = chunksByWorker.get(worker.getWorkerId());
        if (store != null) {
            store.remove(chunkId);
        }
    }

    /** Test hook: makes every call to this worker fail as if it were unreachable. */
    public void killWorker(String workerId) {
        killedWorkerIds.add(workerId);
    }

    public void reviveWorker(String workerId) {
        killedWorkerIds.remove(workerId);
    }

    /** Test hook: makes a subsequent getChunk for this chunk on this worker report corruption. */
    public void corruptChunk(String workerId, String chunkId) {
        corruptChunksByWorker.computeIfAbsent(workerId, id -> ConcurrentHashMap.newKeySet()).add(chunkId);
    }

    public boolean hasChunk(String workerId, String chunkId) {
        Map<String, byte[]> store = chunksByWorker.get(workerId);
        return store != null && store.containsKey(chunkId);
    }

    private void assertReachable(Worker worker) {
        if (killedWorkerIds.contains(worker.getWorkerId())) {
            throw new WorkerUnavailableException("Worker " + worker.getWorkerId() + " is down (fake profile)");
        }
    }
}
