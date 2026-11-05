package com.example.controller.exception;

public class ObjectNotFoundException extends RuntimeException {

    public ObjectNotFoundException(String objectId) {
        super("Object " + objectId + " not found");
    }
}
