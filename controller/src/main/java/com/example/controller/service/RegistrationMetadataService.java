package com.example.controller.service;

import com.example.common.dto.RegisterRequest;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.InvalidRequestException;
import com.example.controller.repository.WorkerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@Service
public class RegistrationMetadataService {

    private final WorkerRepository workerRepository;
    private final RegistrationInventoryValidator inventoryValidator;

    public RegistrationMetadataService(
            WorkerRepository workerRepository,
            RegistrationInventoryValidator inventoryValidator) {
        this.workerRepository = workerRepository;
        this.inventoryValidator = inventoryValidator;
    }

    @Transactional
    public RegistrationAttempt begin(RegisterRequest request) {
        if (request == null) {
            throw new InvalidRequestException(
                    "Registration request is required");
        }

        String workerId = request.workerId();

        if (workerId == null
                || !workerId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new InvalidRequestException(
                    "Worker ID must start with a letter or digit and "
                    + "contain at most 64 letters, digits, '_' or '-'");
        }

        String url = validateUrl(request.url());

        // Validate the inventory before changing worker state.
        inventoryValidator.validate(request.chunks());

        Worker worker = workerRepository
                .findByIdForUpdate(workerId)
                .orElseGet(Worker::new);

        String token = UUID.randomUUID().toString();

        worker.setWorkerId(workerId);
        worker.setUrl(url);
        worker.setStatus(WorkerStatus.REGISTERING);
        worker.setRegistrationToken(token);
        worker.setLastHeartbeat(Instant.now());

        workerRepository.save(worker);

        return new RegistrationAttempt(workerId, url, token);
    }

    private String validateUrl(String value) {
        if (value == null || value.length() > 255) {
            throw new InvalidRequestException(
                    "Worker URL is required and must not exceed 255 characters");
        }

        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new InvalidRequestException("Invalid worker URL");
        }

        boolean allowedScheme =
                "http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme());

        String path = uri.getRawPath();

        if (!allowedScheme
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (path != null && !path.isEmpty() && !path.equals("/"))
                || uri.getPort() == 0
                || uri.getPort() > 65535) {
            throw new InvalidRequestException(
                    "Worker URL must be http(s)://host[:port] "
                    + "without credentials, query, fragment, or a path");
        }

        return value.endsWith("/")
                ? value.substring(0, value.length() - 1)
                : value;
    }

    public record RegistrationAttempt(
            String workerId,
            String url,
            String token) {
    }
}