package com.example.worker.storage;

import com.example.common.ChecksumUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.UUID;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;


class ChunkStoreTest {

    @TempDir
    Path storageDirectory;

    @Test
    void chunkSurvivesReopeningStore() throws Exception {
        String chunkId = UUID.randomUUID().toString();
        byte[] original = {0, 1, 2, 127, (byte) 255};
        String checksum = ChecksumUtil.sha256(original);

        try (ChunkStore store = new ChunkStore(storageDirectory)) {
            assertTrue(store.put(
                    chunkId,
                    new ByteArrayInputStream(original),
                    checksum));
        }

        try (ChunkStore reopened = new ChunkStore(storageDirectory)) {
            ChunkStore.StoredChunk restored = reopened.get(chunkId);
            assertArrayEquals(original, restored.data());
            assertEquals(checksum, restored.checksum());
        }
    }
    @Test
void repeatedUploadIsSafe() throws Exception {
    String chunkId = UUID.randomUUID().toString();
    byte[] original = {10, 20, 30, 40};
    String checksum = ChecksumUtil.sha256(original);

    try (ChunkStore store = new ChunkStore(storageDirectory)) {
        assertTrue(store.put(
                chunkId,
                new ByteArrayInputStream(original),
                checksum));

        assertFalse(store.put(
                chunkId,
                new ByteArrayInputStream(original),
                checksum));

        assertArrayEquals(original, store.get(chunkId).data());
    }
    }
    @Test
void conflictingUploadPreservesOriginalChunk() throws Exception {
    String chunkId = UUID.randomUUID().toString();
    byte[] original = {1, 2, 3};
    byte[] different = {9, 8, 7};

    try (ChunkStore store = new ChunkStore(storageDirectory)) {
        store.put(
                chunkId,
                new ByteArrayInputStream(original),
                ChecksumUtil.sha256(original));

        ChunkStorageException error = assertThrows(
                ChunkStorageException.class,
                () -> store.put(
                        chunkId,
                        new ByteArrayInputStream(different),
                        ChecksumUtil.sha256(different)));

        assertEquals(
                ChunkStorageException.Kind.CONFLICT,
                error.getKind());

        assertArrayEquals(original, store.get(chunkId).data());
    }
}
@Test
void wrongChecksumDoesNotPublishChunk() throws Exception {
    String chunkId = UUID.randomUUID().toString();
    byte[] data = {1, 2, 3};
    String wrongChecksum = ChecksumUtil.sha256(new byte[]{9, 8, 7});

    try (ChunkStore store = new ChunkStore(storageDirectory)) {
        ChunkStorageException uploadError = assertThrows(
                ChunkStorageException.class,
                () -> store.put(
                        chunkId,
                        new ByteArrayInputStream(data),
                        wrongChecksum));

        assertEquals(
                ChunkStorageException.Kind.INVALID_REQUEST,
                uploadError.getKind());

        ChunkStorageException readError = assertThrows(
                ChunkStorageException.class,
                () -> store.get(chunkId));

        assertEquals(
                ChunkStorageException.Kind.NOT_FOUND,
                readError.getKind());
    }
}
@Test
void corruptedStoredChunkIsRejected() throws Exception {
    String chunkId = UUID.randomUUID().toString();
    byte[] original = {10, 20, 30, 40};

    try (ChunkStore store = new ChunkStore(storageDirectory)) {
        store.put(
                chunkId,
                new ByteArrayInputStream(original),
                ChecksumUtil.sha256(original));

        Path chunkFile = storageDirectory.resolve(chunkId + ".chunk");
        byte[] storedRecord = Files.readAllBytes(chunkFile);

        // Simulate damage to the last payload byte.
        storedRecord[storedRecord.length - 1] ^= 1;
        Files.write(chunkFile, storedRecord);

        ChunkStorageException error = assertThrows(
                ChunkStorageException.class,
                () -> store.get(chunkId));

        assertEquals(
                ChunkStorageException.Kind.CORRUPT,
                error.getKind());
    }
}
@Test
void deletionIsSafeToRetryAndSurvivesReopening() throws Exception {
    String chunkId = UUID.randomUUID().toString();
    byte[] data = {1, 2, 3};

    try (ChunkStore store = new ChunkStore(storageDirectory)) {
        store.put(
                chunkId,
                new ByteArrayInputStream(data),
                ChecksumUtil.sha256(data));

        store.delete(chunkId);
        store.delete(chunkId);
    }

    try (ChunkStore reopened = new ChunkStore(storageDirectory)) {
        ChunkStorageException error = assertThrows(
                ChunkStorageException.class,
                () -> reopened.get(chunkId));

        assertEquals(
                ChunkStorageException.Kind.NOT_FOUND,
                error.getKind());
    }
}
@Test
void inventoryExcludesTemporaryAndCorruptedChunks() throws Exception {
    String healthyId = UUID.randomUUID().toString();
    String corruptId = UUID.randomUUID().toString();
    byte[] data = {10, 20, 30};
    String checksum = ChecksumUtil.sha256(data);

    try (ChunkStore store = new ChunkStore(storageDirectory)) {
        store.put(healthyId, new ByteArrayInputStream(data), checksum);
        store.put(corruptId, new ByteArrayInputStream(data), checksum);

        Path corruptFile = storageDirectory.resolve(corruptId + ".chunk");
        byte[] record = Files.readAllBytes(corruptFile);
        record[record.length - 1] ^= 1;
        Files.write(corruptFile, record);

        Path temporaryFile = storageDirectory.resolve(
                ".chunk-" + UUID.randomUUID() + ".partial.tmp");
        Files.write(temporaryFile, new byte[]{1, 2});

        var inventory = store.listChunks();

        assertEquals(1, inventory.size());
        assertEquals(healthyId, inventory.get(0).chunkId());
        assertEquals(data.length, inventory.get(0).size());
        assertEquals(checksum, inventory.get(0).checksum());

        assertTrue(Files.exists(corruptFile));
        assertTrue(Files.exists(temporaryFile));
    }
}
}