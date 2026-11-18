package com.example.worker.storage;

import com.example.common.ChecksumUtil;
import com.example.common.Constants;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import com.example.common.dto.ChunkInfo;
import java.nio.file.DirectoryIteratorException;
import java.util.ArrayList;
import java.util.List;

import static com.example.worker.storage.ChunkStorageException.Kind.*;

/** Stores immutable, checksummed chunks in a directory owned by one worker process. */
public final class ChunkStore implements AutoCloseable {

    private static final int MAGIC = 0x4D444353; // MDCS
    private static final int VERSION = 1;
    // magic, version, UUID, payload length, SHA-256
    private static final int HEADER_SIZE = 4 + 4 + 16 + 4 + 32;

    private final Path directory;
    private final FileChannel ownershipChannel;
    private final FileLock ownershipLock;
    private final Object[] locks = new Object[128];

    public ChunkStore(Path directory) throws IOException {
        Files.createDirectories(directory.toAbsolutePath().normalize());
        this.directory = directory.toRealPath();
        Arrays.setAll(locks, ignored -> new Object());
        ownershipChannel = FileChannel.open(this.directory.resolve(".worker.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            ownershipLock = ownershipChannel.tryLock();
            if (ownershipLock == null) {
                throw new IOException("Storage directory is already used by another worker");
            }
            // The process lock makes it safe to remove staging files left by a previous run.
            try (var abandoned = Files.newDirectoryStream(this.directory, ".chunk-*.tmp")) {
                for (Path file : abandoned) {
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        Files.delete(file);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            ownershipChannel.close();
            if (e instanceof OverlappingFileLockException) {
                throw new IOException("Storage directory is already used by another worker", e);
            }
            throw e;
        }
    }

    /** Returns true for a newly created chunk, false for an identical retry. */
    public boolean put(String chunkId, InputStream input, String expectedChecksum) throws IOException {
        UUID id = parseId(chunkId);
        if (expectedChecksum == null || !expectedChecksum.matches("[0-9a-fA-F]{64}")) {
            throw new ChunkStorageException(INVALID_REQUEST, "X-Checksum must be a SHA-256 hex string");
        }
        ensureOpen();
        // Read at most one extra byte so a large request cannot allocate an unbounded array.
        byte[] data = input.readNBytes(Constants.CHUNK_SIZE + 1);
        if (data.length == 0 || data.length > Constants.CHUNK_SIZE) {
            throw new ChunkStorageException(INVALID_REQUEST,
                    "A chunk must contain between 1 and " + Constants.CHUNK_SIZE + " bytes");
        }
        String checksum = ChecksumUtil.sha256(data);
        if (!checksum.equalsIgnoreCase(expectedChecksum)) {
            throw new ChunkStorageException(INVALID_REQUEST, "Chunk checksum does not match X-Checksum");
        }

        synchronized (lockFor(id)) {
            ensureOpen();
            if (hasDeletionMarker(id)) {
    throw new ChunkStorageException(
            CONFLICT,
            "Chunk " + id
                    + " was deleted and cannot be uploaded again");
}
            Path target = chunkPath(id);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    StoredChunk existing = readRecord(id);
                    if (!Arrays.equals(existing.data(), data)) {
                        throw new ChunkStorageException(CONFLICT,
                                "Chunk ID already contains different data");
                    }
                    return false;
                } catch (ChunkStorageException error) {
                    if (error.getKind() != CORRUPT) throw error;
                    // A verified PUT can atomically replace an unreadable replica.
                    // A healthy conflicting value and a deletion marker still reject PUT.
                }
            }

            Path temporary = Files.createTempFile(directory, ".chunk-" + id + ".", ".tmp");
            try {
                ByteBuffer record = ByteBuffer.allocate(HEADER_SIZE + data.length);
                record.putInt(MAGIC).putInt(VERSION);
                record.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
                record.putInt(data.length).put(HexFormat.of().parseHex(checksum)).put(data).flip();
                try (FileChannel output = FileChannel.open(temporary,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    while (record.hasRemaining()) {
                        output.write(record);
                    }
                    output.force(true);
                }
                // Same directory/filesystem. If atomic move is unsupported, fail rather than
                // expose a partially copied final record. Both cooperating writers hold our locks.
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return true;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public StoredChunk get(String chunkId) throws IOException {
    UUID id = parseId(chunkId);

    synchronized (lockFor(id)) {
        ensureOpen();

        if (hasDeletionMarker(id)) {
            throw new ChunkStorageException(
                    NOT_FOUND,
                    "Chunk " + id + " was deleted");
        }

        return readRecord(id);
    }
}
public void delete(String chunkId) throws IOException {
    UUID id = parseId(chunkId);

    synchronized (lockFor(id)) {
        ensureOpen();

        // Record deletion before removing any chunk bytes.
        // This also applies when the chunk has not arrived yet.
        writeDeletionMarker(id);

        Files.deleteIfExists(chunkPath(id));

        // Do not acknowledge success if storage became unavailable.
        ensureOpen();

        // Keep the marker to reject delayed or repeated PUTs.
    }
}
public void evict(String chunkId) throws IOException {
    UUID id = parseId(chunkId);
    synchronized (lockFor(id)) {
        ensureOpen();
        Files.deleteIfExists(chunkPath(id));
        ensureOpen();
    }
}

public List<ChunkInfo> listChunks() throws IOException {
    ensureOpen();
    List<ChunkInfo> inventory = new ArrayList<>();

    try (var files = Files.newDirectoryStream(directory, "*.chunk")) {
        for (Path file : files) {
            String filename = file.getFileName().toString();

            try {
                UUID id = parseId(
                        filename.substring(0, filename.length() - 6));

                StoredChunk chunk = get(id.toString());

                inventory.add(new ChunkInfo(
                        id.toString(),
                        chunk.data().length,
                        chunk.checksum()));

            } catch (ChunkStorageException error) {
                if (error.getKind() == NOT_FOUND) {
                    // Another request may have deleted this chunk.
                    continue;
                }

                if (error.getKind() == CORRUPT
                        || error.getKind() == INVALID_REQUEST) {
                    System.getLogger(ChunkStore.class.getName())
                            .log(System.Logger.Level.WARNING,
                                    "Excluding chunk file " + filename
                                            + ": " + error.getMessage());
                    continue;
                }

                throw error;
            }
        }
    } catch (DirectoryIteratorException error) {
        throw error.getCause();
    }

    ensureOpen();
    return List.copyOf(inventory);
}
    private StoredChunk readRecord(UUID id) throws IOException {
        try (FileChannel input = FileChannel.open(chunkPath(id),
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = input.size();
            if (size <= HEADER_SIZE || size > HEADER_SIZE + Constants.CHUNK_SIZE) {
                throw corrupt(id);
            }
            ByteBuffer record = ByteBuffer.allocate((int) size);
            while (record.hasRemaining()) {
                if (input.read(record) == -1) {
                    throw corrupt(id);
                }
            }
            record.flip();
            if (record.getInt() != MAGIC || record.getInt() != VERSION
                    || record.getLong() != id.getMostSignificantBits()
                    || record.getLong() != id.getLeastSignificantBits()) {
                throw corrupt(id);
            }
            int payloadLength = record.getInt();
            if (payloadLength != size - HEADER_SIZE) {
                throw corrupt(id);
            }
            byte[] expectedHash = new byte[32];
            record.get(expectedHash);
            byte[] data = new byte[payloadLength];
            record.get(data);
            String checksum = HexFormat.of().formatHex(expectedHash);
            if (!ChecksumUtil.sha256(data).equals(checksum)) {
                throw corrupt(id);
            }
            return new StoredChunk(data, checksum);
        } catch (NoSuchFileException e) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Chunk storage directory is unavailable", e);
            }
            throw new ChunkStorageException(NOT_FOUND, "Chunk " + id + " does not exist");
        }
    }

    private UUID parseId(String chunkId) {
        try {
            UUID id = UUID.fromString(chunkId);
            if (!id.toString().equalsIgnoreCase(chunkId)) {
                throw new IllegalArgumentException();
            }
            return id;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ChunkStorageException(INVALID_REQUEST, "Chunk ID must be a canonical UUID");
        }
    }
    private Path deletionMarkerPath(UUID id) {
    return directory.resolve(id + ".deleted");
}

private boolean hasDeletionMarker(UUID id) throws IOException {
    try {
        BasicFileAttributes attributes = Files.readAttributes(
                deletionMarkerPath(id),
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);

        if (!attributes.isRegularFile()) {
            throw new IOException(
                    "Invalid deletion marker for chunk " + id);
        }

        // Presence is authoritative. Even an empty marker left by
        // an interrupted write must prevent chunk resurrection.
        return true;

    } catch (NoSuchFileException missing) {
        // Missing storage must not be mistaken for a missing marker.
        ensureOpen();
        return false;
    }
}

private void writeDeletionMarker(UUID id) throws IOException {
    // Called while holding this chunk's lock.
    // CREATE preserves an existing marker during repeated DELETEs.
    try (FileChannel output = FileChannel.open(
            deletionMarkerPath(id),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {

        ByteBuffer marker = ByteBuffer.wrap(new byte[] {1});

        while (marker.hasRemaining()) {
            output.write(marker);
        }

        output.force(true);
    }
}
    private Path chunkPath(UUID id) {
        return directory.resolve(id + ".chunk");
    }

    private Object lockFor(UUID id) {
        return locks[id.hashCode() & (locks.length - 1)];
    }

    private ChunkStorageException corrupt(UUID id) {
        return new ChunkStorageException(CORRUPT, "Chunk " + id + " failed integrity verification");
    }

    private void ensureOpen() throws IOException {
        if (!ownershipChannel.isOpen() || !ownershipLock.isValid()) {
            throw new IOException("Chunk storage is closed");
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Chunk storage directory is unavailable");
        }
    }

    @Override
    public void close() throws IOException {
        ownershipChannel.close(); // Also releases the process lock.
    }

    public record StoredChunk(byte[] data, String checksum) { }
}
