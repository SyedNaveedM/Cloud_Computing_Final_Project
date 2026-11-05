package com.example.controller.client;

public class ChunkNotFoundException extends RuntimeException {

    public ChunkNotFoundException(String message) {
        super(message);
    }
}
