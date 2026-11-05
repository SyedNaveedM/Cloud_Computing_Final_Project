package com.example.controller.exception;

public class InsufficientWorkersException extends RuntimeException {

    public InsufficientWorkersException(String message) {
        super(message);
    }
}
