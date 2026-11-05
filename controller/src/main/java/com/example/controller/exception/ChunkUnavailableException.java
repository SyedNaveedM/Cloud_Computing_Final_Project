package com.example.controller.exception;

public class ChunkUnavailableException extends RuntimeException {

    public ChunkUnavailableException(String message) {
        super(message);
    }
}
