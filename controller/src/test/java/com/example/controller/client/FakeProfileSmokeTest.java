package com.example.controller.client;

import com.example.controller.domain.WorkerStatus;
import com.example.controller.repository.WorkerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest
@ActiveProfiles("fake")
class FakeProfileSmokeTest {

    @Autowired
    private WorkerClient workerClient;

    @Autowired
    private WorkerRepository workerRepository;

    @Test
    void seedsFourAliveWorkersAndUsesInMemoryClient() {
        assertInstanceOf(InMemoryWorkerClient.class, workerClient);
        assertEquals(4, workerRepository.findByStatus(WorkerStatus.ALIVE).size());
    }
}
