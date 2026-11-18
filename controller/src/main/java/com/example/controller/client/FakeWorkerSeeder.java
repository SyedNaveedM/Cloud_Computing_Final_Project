package com.example.controller.client;

import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.repository.WorkerRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@Profile("fake")
@Order(0)
public class FakeWorkerSeeder implements ApplicationRunner {

    private static final int FAKE_WORKER_COUNT = 4;

    private final WorkerRepository workerRepository;

    public FakeWorkerSeeder(WorkerRepository workerRepository) {
        this.workerRepository = workerRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (int i = 1; i <= FAKE_WORKER_COUNT; i++) {
            String workerId = "worker" + i;

            Worker worker = workerRepository.findById(workerId)
                    .orElseGet(() -> new Worker(
                            workerId,
                            "http://fake-" + workerId,
                            Instant.now(),
                            WorkerStatus.ALIVE,
                            0L,
                            0));

            if (worker.getRegistrationToken() == null
                    || worker.getRegistrationToken().isBlank()) {

                worker.setRegistrationToken(UUID.randomUUID().toString());
                workerRepository.save(worker);
            }
        }
    }
}