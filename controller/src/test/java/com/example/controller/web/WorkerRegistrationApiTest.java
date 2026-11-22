package com.example.controller.web;

import com.example.common.dto.RegisterRequest;
import com.example.controller.client.WorkerClient;
import com.example.controller.client.WorkerUnavailableException;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.repository.WorkerRepository;
import com.example.controller.service.RegistrationMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:worker-registration-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerRegistrationApiTest {

    private static final String ENDPOINT =
            "/internal/workers/register";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private RegistrationMetadataService registrationService;

    @MockitoBean(enforceOverride = true)
    private WorkerClient workerClient;

    private String workerId;
    private RegisterRequest registration;

    @BeforeEach
    void setUp() {
        workerId = "test-" + UUID.randomUUID();

        registration = new RegisterRequest(
                workerId,
                "http://localhost:8082",
                List.of());
    }

    @Test
    void successfulVerificationReturns200AndMakesWorkerAlive()
            throws Exception {

        when(workerClient.listChunks(any(Worker.class)))
                .thenReturn(List.of());

        postRegistration(registration)
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Registration-Token"))
                .andExpect(content().string(""))
                .andExpect(header()
                        .doesNotExist(HttpHeaders.RETRY_AFTER));

        Worker stored = workerRepository.findById(workerId)
                .orElseThrow();

        assertEquals(WorkerStatus.ALIVE, stored.getStatus());
        assertEquals("http://localhost:8082", stored.getUrl());
        assertNotNull(stored.getRegistrationToken());

        verify(workerClient).listChunks(any(Worker.class));
    }

    @Test
    void unavailableInventoryReturns503WithRetryAdvice()
            throws Exception {

        when(workerClient.listChunks(any(Worker.class)))
                .thenThrow(new WorkerUnavailableException(
                        "Inventory request timed out"));

        postRegistration(registration)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header()
                        .string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error")
                        .value("Service Unavailable"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value(ENDPOINT));

        assertEquals(
                WorkerStatus.REGISTERING,
                workerRepository.findById(workerId)
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void newerRegistrationMakesOlderRequestReturn409()
            throws Exception {

        when(workerClient.listChunks(any(Worker.class)))
                .thenAnswer(invocation -> {
                    // Start a newer registration before the
                    // earlier request finishes verification.
                    registrationService.begin(new RegisterRequest(
                            workerId,
                            "http://localhost:8083",
                            List.of()));

                    return List.of();
                });

        postRegistration(registration)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value(ENDPOINT));

        Worker stored = workerRepository.findById(workerId)
                .orElseThrow();

        assertEquals(
                WorkerStatus.REGISTERING,
                stored.getStatus());

        assertEquals(
                "http://localhost:8083",
                stored.getUrl());
    }

    @Test
    void invalidWorkerUrlReturns400WithoutRegisteringWorker()
            throws Exception {

        RegisterRequest invalid = new RegisterRequest(
                workerId,
                "ftp://localhost:8082",
                List.of());

        postRegistration(invalid)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value(ENDPOINT));

        assertFalse(workerRepository.existsById(workerId));
        verifyNoInteractions(workerClient);
    }

    @Test
    void malformedJsonReturns400BeforeRegistrationStarts()
            throws Exception {

        long workersBefore = workerRepository.count();

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value(ENDPOINT));

        assertEquals(workersBefore, workerRepository.count());
        verifyNoInteractions(workerClient);
    }

    private ResultActions postRegistration(RegisterRequest request)
            throws Exception {

        return mockMvc.perform(post(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }
}
