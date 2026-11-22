package com.example.controller.web;

import com.example.common.ChecksumUtil;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("fake")
class ObjectApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkerRepository workerRepository;
    @Autowired
    private StoredObjectRepository objectRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullLifecycleOverHttp() throws Exception {
        byte[] data = randomBytes(2000);
        String expectedChecksum = ChecksumUtil.sha256(data);

        MockMultipartFile file = new MockMultipartFile("file", "demo.bin", "application/octet-stream", data);

        String uploadResponse = mockMvc.perform(multipart("/api/v1/objects").file(file)
                        .param("metadata", "{\"owner\":\"alice\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checksum").value(expectedChecksum))
                .andExpect(jsonPath("$.customMetadata.owner").value("alice"))
                .andReturn().getResponse().getContentAsString();

        JsonNode uploaded = objectMapper.readTree(uploadResponse);
        String id = uploaded.get("id").asText();

        mockMvc.perform(get("/api/v1/objects/{id}/metadata", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunks").isArray())
                .andExpect(jsonPath("$.chunks[0].replicas.length()").value(2));

        mockMvc.perform(patch("/api/v1/objects/{id}/metadata", id)
                        .contentType("application/json")
                        .content("{\"owner\":null,\"project\":\"store\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customMetadata.owner").doesNotExist())
                .andExpect(jsonPath("$.customMetadata.project").value("store"));

        mockMvc.perform(get("/api/v1/objects").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + id + "')]").exists());

        MvcResult asyncResult = mockMvc.perform(get("/api/v1/objects/{id}", id))
                .andExpect(request().asyncStarted())
                .andReturn();
        byte[] downloaded = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals(expectedChecksum, ChecksumUtil.sha256(downloaded));

        mockMvc.perform(delete("/api/v1/objects/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/objects/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadRejectsEmptyFileWith400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.bin", "application/octet-stream", new byte[0]);

        mockMvc.perform(multipart("/api/v1/objects").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/objects"));
    }

    @Test
    void uploadReturns503WhenFewerThanTwoWorkersAreAlive() throws Exception {
        long objectsBefore = objectRepository.count();
        workerRepository.findAll().forEach(worker -> {
            worker.setStatus(WorkerStatus.DEAD);
            workerRepository.save(worker);
        });
        try {
            MockMultipartFile file = new MockMultipartFile("file", "f.bin", "application/octet-stream", randomBytes(10));
            mockMvc.perform(multipart("/api/v1/objects").file(file))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.error").value("Service Unavailable"));
            assertEquals(objectsBefore, objectRepository.count());
        } finally {
            workerRepository.findAll().forEach(worker -> {
                worker.setStatus(WorkerStatus.ALIVE);
                workerRepository.save(worker);
            });
        }
    }

    @Test
    void getUnknownObjectReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/objects/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @ParameterizedTest
    @CsvSource({
            "page, abc",
            "size, abc",
            "page, 2147483648",
            "size, 2147483648"
    })
    void invalidPaginationReturns400(String parameter, String value) throws Exception {
        mockMvc.perform(get("/api/v1/objects").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message")
                        .value("Invalid value for request parameter '" + parameter + "'"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/objects"));
    }

    private byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(11).nextBytes(data);
        return data;
    }
}
