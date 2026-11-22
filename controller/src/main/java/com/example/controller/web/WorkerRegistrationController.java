package com.example.controller.web;

import com.example.common.dto.ErrorResponse;
import com.example.common.dto.RegisterRequest;
import com.example.controller.service.RegistrationCoordinator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/internal/workers")
public class WorkerRegistrationController {

    private final RegistrationCoordinator coordinator;

    public WorkerRegistrationController(
            RegistrationCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping(
            value = "/register",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> register(
            @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {

        var reply = coordinator.registerWithToken(request);
        return switch (reply.result()) {
            case COMPLETED -> ResponseEntity.ok()
                    .header("X-Registration-Token", reply.token()).build();

            case STALE_ATTEMPT -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(error(
                            HttpStatus.CONFLICT,
                            "A newer registration replaced this attempt",
                            httpRequest));

            case RETRY_REQUIRED -> ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "5")
                    .body(error(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Registration verification could not complete; "
                                    + "retry after the suggested delay",
                            httpRequest));
        };
    }

    private ErrorResponse error(
            HttpStatus status,
            String message,
            HttpServletRequest request) {

        return new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI());
    }
}
