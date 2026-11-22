package com.example.worker.web;
import org.springframework.web.server.ResponseStatusException;
import com.example.common.dto.ErrorResponse;
import com.example.worker.storage.ChunkStorageException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.time.Instant;

@RestControllerAdvice(assignableTypes = {
        ChunkController.class,
        WorkerInventoryController.class
})
public class WorkerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkerExceptionHandler.class);

    @ExceptionHandler(ChunkStorageException.class)
    public ResponseEntity<ErrorResponse> storageError(ChunkStorageException error, HttpServletRequest request) {
        HttpStatus status = switch (error.getKind()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT, CORRUPT -> HttpStatus.CONFLICT;
        };
        return response(status, error.getMessage(), request);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorResponse> ioError(IOException error, HttpServletRequest request) {
        log.warn("Chunk I/O failed for {}", request.getRequestURI(), error);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "Chunk storage or request stream is unavailable", request);
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
    }
    @ExceptionHandler(ResponseStatusException.class)
public ResponseEntity<ErrorResponse> httpError(
        ResponseStatusException error,
        HttpServletRequest request) {

    HttpStatus status = HttpStatus.valueOf(
            error.getStatusCode().value());

    return response(status, error.getReason(), request);
}
}
