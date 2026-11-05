package com.example.controller.client;

public class CorruptChunkException extends RuntimeException {

    public CorruptChunkException(String message) {
        super(message);
    }
}
