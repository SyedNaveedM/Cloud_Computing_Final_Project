package com.example.worker.storage;

public class ChunkStorageException extends RuntimeException {

    public enum Kind { INVALID_REQUEST, NOT_FOUND, CONFLICT, CORRUPT }

    private final Kind kind;

    public ChunkStorageException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
