package com.example.worker.storage;

import com.example.common.ChecksumUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkDeletionMarkerTest {

    @TempDir
    Path storageDirectory;

    @Test
    void deleteDuringInputReadingPreventsChunkPublication() throws Exception {
        String chunkId = UUID.randomUUID().toString();
        byte[] data = {10, 20, 30};

        try (ChunkStore store = new ChunkStore(storageDirectory)) {
            InputStream delayedInput = new InputStream() {

                private final ByteArrayInputStream delegate =
                        new ByteArrayInputStream(data);

                private boolean deletionTriggered;

                @Override
                public int read() throws IOException {
                    if (!deletionTriggered) {
                        deletionTriggered = true;

                        // PUT has started, but has not published its bytes.
                        // Complete DELETE before allowing the input to continue.
                        store.delete(chunkId);
                    }

                    return delegate.read();
                }
            };

            ChunkStorageException failure = assertThrows(
                    ChunkStorageException.class,
                    () -> store.put(
                            chunkId,
                            delayedInput,
                            ChecksumUtil.sha256(data)));

            assertEquals(
                    ChunkStorageException.Kind.CONFLICT,
                    failure.getKind());

            assertTrue(Files.exists(
                    storageDirectory.resolve(chunkId + ".deleted")));

            assertFalse(Files.exists(
                    storageDirectory.resolve(chunkId + ".chunk")));

            assertRetired(store, chunkId, data);
        }
    }

    @Test
    void deletedIdRemainsBlockedAfterReopening() throws Exception {
        String chunkId = UUID.randomUUID().toString();
        byte[] data = {1, 2, 3};

        try (ChunkStore store = new ChunkStore(storageDirectory)) {
            store.put(
                    chunkId,
                    new ByteArrayInputStream(data),
                    ChecksumUtil.sha256(data));

            store.delete(chunkId);
        }

        try (ChunkStore reopened = new ChunkStore(storageDirectory)) {
            assertRetired(reopened, chunkId, data);

            assertFalse(Files.exists(
                    storageDirectory.resolve(chunkId + ".chunk")));
        }
    }

    @Test
    void interruptedDeletionHidesLeftoverBytesAndCanBeRetried()
            throws Exception {

        String chunkId = UUID.randomUUID().toString();
        byte[] data = {4, 5, 6};

        Path chunkFile = storageDirectory.resolve(chunkId + ".chunk");
        Path markerFile = storageDirectory.resolve(chunkId + ".deleted");

        try (ChunkStore store = new ChunkStore(storageDirectory)) {
            store.put(
                    chunkId,
                    new ByteArrayInputStream(data),
                    ChecksumUtil.sha256(data));
        }

        // Recreate an interrupted deletion:
        // the marker exists, but the original chunk bytes remain.
        // An empty marker must also block resurrection.
        Files.createFile(markerFile);

        try (ChunkStore reopened = new ChunkStore(storageDirectory)) {
            assertTrue(Files.exists(chunkFile));

            // Leftover bytes must not be served or reported as usable.
            assertRetired(reopened, chunkId, data);

            // Retrying DELETE finishes removing the bytes.
            reopened.delete(chunkId);

            assertFalse(Files.exists(chunkFile));
            assertTrue(Files.exists(markerFile));

            // Repeated DELETE remains safe and keeps the marker.
            reopened.delete(chunkId);
            assertRetired(reopened, chunkId, data);
        }
    }

    @Test
    void unusableMarkerPathPreservesBytesAndFailsClosed() throws Exception {
        String chunkId = UUID.randomUUID().toString();
        byte[] data = {7, 8, 9};

        Path chunkFile = storageDirectory.resolve(chunkId + ".chunk");
        Path markerPath = storageDirectory.resolve(chunkId + ".deleted");

        try (ChunkStore store = new ChunkStore(storageDirectory)) {
            store.put(
                    chunkId,
                    new ByteArrayInputStream(data),
                    ChecksumUtil.sha256(data));

            byte[] originalRecord = Files.readAllBytes(chunkFile);

            // A directory cannot be opened as the marker file.
            // This creates a deterministic storage failure.
            Files.createDirectory(markerPath);

            assertThrows(
                    IOException.class,
                    () -> store.delete(chunkId));

            // An invalid marker must not be treated as "no marker".
            assertThrows(
                    IOException.class,
                    () -> store.put(
                            chunkId,
                            new ByteArrayInputStream(data),
                            ChecksumUtil.sha256(data)));

            assertThrows(
                    IOException.class,
                    () -> store.get(chunkId));

            assertThrows(
                    IOException.class,
                    () -> store.listChunks());

            // Failed operations must preserve the existing bytes.
            assertArrayEquals(
                    originalRecord,
                    Files.readAllBytes(chunkFile));
        }
    }

    private void assertRetired(
            ChunkStore store,
            String chunkId,
            byte[] data) throws IOException {

        ChunkStorageException putFailure = assertThrows(
                ChunkStorageException.class,
                () -> store.put(
                        chunkId,
                        new ByteArrayInputStream(data),
                        ChecksumUtil.sha256(data)));

        assertEquals(
                ChunkStorageException.Kind.CONFLICT,
                putFailure.getKind());

        ChunkStorageException getFailure = assertThrows(
                ChunkStorageException.class,
                () -> store.get(chunkId));

        assertEquals(
                ChunkStorageException.Kind.NOT_FOUND,
                getFailure.getKind());

        assertTrue(
                store.listChunks().stream()
                        .noneMatch(chunk -> chunk.chunkId().equals(chunkId)),
                "A deleted chunk must not appear in inventory");
    }
}