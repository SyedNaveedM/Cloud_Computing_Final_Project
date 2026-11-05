package com.example.controller.client;

public class ChunkRejectedException extends RuntimeException {

    public ChunkRejectedException(String message) {
        super(message);
    }

    public ChunkRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
