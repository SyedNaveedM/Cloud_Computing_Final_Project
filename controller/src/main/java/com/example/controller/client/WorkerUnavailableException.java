package com.example.controller.client;

public class WorkerUnavailableException extends RuntimeException {

    public WorkerUnavailableException(String message) {
        super(message);
    }

    public WorkerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
