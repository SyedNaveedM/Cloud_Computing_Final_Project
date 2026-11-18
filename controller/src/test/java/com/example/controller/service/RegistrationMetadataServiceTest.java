package com.example.controller.service;

import com.example.common.dto.RegisterRequest;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.InvalidRequestException;
import com.example.controller.repository.WorkerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class RegistrationMetadataServiceTest {

    @Autowired
    private RegistrationMetadataService service;

    @Autowired
    private WorkerRepository workerRepository;

    @Test
    void returningWorkerIsExcludedFromAliveWorkers() {
        String workerId = addAliveWorker();

        var attempt = service.begin(new RegisterRequest(
                workerId, "http://localhost:8082/", List.of()));

        Worker stored = workerRepository.findById(workerId).orElseThrow();

        assertEquals(WorkerStatus.REGISTERING, stored.getStatus());
        assertEquals(attempt.token(), stored.getRegistrationToken());
        assertEquals("http://localhost:8082", stored.getUrl());

        assertFalse(workerRepository.findByStatus(WorkerStatus.ALIVE)
                .stream()
                .anyMatch(worker -> workerId.equals(worker.getWorkerId())));
    }

    @Test
    void retryReusesWorkerRecordAndReplacesAttemptToken() {
        String workerId = "test-" + UUID.randomUUID();
        long countBefore = workerRepository.count();

        var request = new RegisterRequest(
                workerId, "http://localhost:8082", List.of());

        var first = service.begin(request);
        var second = service.begin(request);

        assertEquals(countBefore + 1, workerRepository.count());
        assertNotEquals(first.token(), second.token());

        Worker stored = workerRepository.findById(workerId).orElseThrow();

        assertEquals(second.token(), stored.getRegistrationToken());
        assertEquals(WorkerStatus.REGISTERING, stored.getStatus());
    }

    @Test
    void invalidInventoryDoesNotChangeAliveWorker() {
        String workerId = addAliveWorker();

        assertThrows(
                InvalidRequestException.class,
                () -> service.begin(new RegisterRequest(
                        workerId, "http://localhost:8082", null)));

        Worker stored = workerRepository.findById(workerId).orElseThrow();

        assertEquals(WorkerStatus.ALIVE, stored.getStatus());
    }

    private String addAliveWorker() {
        String workerId = "test-" + UUID.randomUUID();

        workerRepository.save(new Worker(
                workerId,
                "http://localhost:8082",
                Instant.now(),
                WorkerStatus.ALIVE,
                0,
                0));

        return workerId;
    }
}