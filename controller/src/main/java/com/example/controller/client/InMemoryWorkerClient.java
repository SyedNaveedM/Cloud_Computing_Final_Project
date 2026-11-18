package com.example.controller.client;

import com.example.common.ChecksumUtil;
import com.example.controller.domain.Worker;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.example.common.dto.ChunkInfo;
import java.util.List;
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
    private final Map<String, Set<String>> deletedChunksByWorker =
        new ConcurrentHashMap<>();

@Override
public synchronized void putChunk(
        Worker worker,
        String chunkId,
        byte[] data,
        String checksum) {

    assertReachable(worker);

    if (!ChecksumUtil.sha256(data).equals(checksum)) {
        throw new ChunkRejectedException(
                "Checksum mismatch for chunk " + chunkId
                        + " on worker " + worker.getWorkerId());
    }

    String workerId = worker.getWorkerId();

    if (deletedChunksByWorker
            .getOrDefault(workerId, Set.of())
            .contains(chunkId)) {

        throw new ChunkRejectedException(
                "Chunk " + chunkId
                        + " was deleted on worker " + workerId
                        + " and cannot be uploaded again");
    }

    byte[] existing = chunksByWorker.getOrDefault(workerId, Map.of()).get(chunkId);
    boolean corrupt = corruptChunksByWorker.getOrDefault(workerId, Set.of()).contains(chunkId);
    if (existing != null && !corrupt && !java.util.Arrays.equals(existing, data)) {
        throw new ChunkRejectedException("Chunk ID already contains different data");
    }
    chunksByWorker
            .computeIfAbsent(workerId, ignored -> new ConcurrentHashMap<>())
            .put(chunkId, data.clone());
    Set<String> corrupted = corruptChunksByWorker.get(workerId);
    if (corrupted != null) corrupted.remove(chunkId);
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
    try {
        deleteChunkConfirmed(worker, chunkId);
    } catch (WorkerUnavailableException ignored) {
        // Existing callers use best-effort deletion.
    }
}

@Override
public synchronized void deleteChunkConfirmed(
        Worker worker,
        String chunkId) {

    // An unreachable worker cannot receive or acknowledge this deletion.
    assertReachable(worker);

    String workerId = worker.getWorkerId();

    // Remember deletion even if the chunk has never arrived.
    deletedChunksByWorker
            .computeIfAbsent(
                    workerId,
                    ignored -> ConcurrentHashMap.newKeySet())
            .add(chunkId);

    Map<String, byte[]> store = chunksByWorker.get(workerId);

    if (store != null) {
        store.remove(chunkId);
    }

    Set<String> corrupted = corruptChunksByWorker.get(workerId);

    if (corrupted != null) {
        corrupted.remove(chunkId);
    }
}
    @Override
public List<ChunkInfo> listChunks(Worker worker) {
    assertReachable(worker);

    Map<String, byte[]> store =
            chunksByWorker.get(worker.getWorkerId());

    if (store == null) {
        return List.of();
    }

    Set<String> corrupted = corruptChunksByWorker.getOrDefault(
            worker.getWorkerId(), Set.of());

    return store.entrySet().stream()
            .filter(entry -> !corrupted.contains(entry.getKey()))
            .map(entry -> new ChunkInfo(
                    entry.getKey(),
                    entry.getValue().length,
                    ChecksumUtil.sha256(entry.getValue())))
            .toList();
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

    @Override
    public synchronized void evictChunk(Worker worker, String chunkId) {
        assertReachable(worker);
        Map<String, byte[]> store = chunksByWorker.get(worker.getWorkerId());
        if (store != null) store.remove(chunkId);
        Set<String> corrupted = corruptChunksByWorker.get(worker.getWorkerId());
        if (corrupted != null) corrupted.remove(chunkId);
    }
}
