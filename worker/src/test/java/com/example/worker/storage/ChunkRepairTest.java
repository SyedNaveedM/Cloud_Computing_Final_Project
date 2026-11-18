package com.example.worker.storage;

import com.example.common.ChecksumUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChunkRepairTest {
    @TempDir Path directory;

    @Test
    void verifiedPutRepairsCorruptBytesButDoesNotOverwriteHealthyDifferentBytes() throws Exception {
        String id = UUID.randomUUID().toString();
        byte[] data = {4, 5, 6};
        try (ChunkStore store = new ChunkStore(directory)) {
            store.put(id, new ByteArrayInputStream(data), ChecksumUtil.sha256(data));
            Files.write(directory.resolve(id + ".chunk"), new byte[]{99});
            assertThrows(ChunkStorageException.class, () -> store.get(id));
            assertTrue(store.put(id, new ByteArrayInputStream(data), ChecksumUtil.sha256(data)));
            assertArrayEquals(data, store.get(id).data());
            byte[] different = {7, 8, 9};
            assertThrows(ChunkStorageException.class,
                    () -> store.put(id, new ByteArrayInputStream(different), ChecksumUtil.sha256(different)));
        }
    }

    @Test
    void evictionAllowsFutureRepairButRetirementStillBlocksIt() throws Exception {
        String id = UUID.randomUUID().toString();
        byte[] data = {1, 2};
        try (ChunkStore store = new ChunkStore(directory)) {
            store.put(id, new ByteArrayInputStream(data), ChecksumUtil.sha256(data));
            store.evict(id);
            assertFalse(Files.exists(directory.resolve(id + ".deleted")));
            assertTrue(store.put(id, new ByteArrayInputStream(data), ChecksumUtil.sha256(data)));
            store.delete(id);
            store.evict(id);
            assertTrue(Files.exists(directory.resolve(id + ".deleted")));
            assertThrows(ChunkStorageException.class,
                    () -> store.put(id, new ByteArrayInputStream(data), ChecksumUtil.sha256(data)));
        }
    }
}
