package com.example.controller.client;

import com.example.common.ChecksumUtil;
import com.example.common.dto.ChunkInfo;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WorkerInventoryClientTest {

    private static final String CHUNK_ID =
            "11111111-1111-4111-8111-111111111111";

    private static final String CHECKSUM =
            ChecksumUtil.sha256(new byte[]{1, 2, 3});

    private static final String INVENTORY_URL =
            "http://localhost:8082/internal/workers/worker1/chunks";

    private MockRestServiceServer server;
    private HttpWorkerClient client;
    private Worker worker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();

        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpWorkerClient(builder.build());

        worker = new Worker(
                "worker1",
                "http://localhost:8082",
                Instant.now(),
                WorkerStatus.REGISTERING,
                0,
                0);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void readsInventoryFromTheWorkerEndpoint() {
        String responseBody = """
                [
                  {
                    "chunkId": "%s",
                    "size": 3,
                    "checksum": "%s"
                  }
                ]
                """.formatted(CHUNK_ID, CHECKSUM);

        respondWith(withSuccess(
                responseBody, MediaType.APPLICATION_JSON));

        List<ChunkInfo> inventory = client.listChunks(worker);

        assertEquals(
                List.of(new ChunkInfo(CHUNK_ID, 3, CHECKSUM)),
                inventory);
    }

    @Test
    void acceptsAnExplicitEmptyInventory() {
        respondWith(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertTrue(client.listChunks(worker).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 206, 302, 404, 503})
    void rejectsUnexpectedHttpStatus(int status) {
        respondWith(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON)
                .body("[]"));

        assertThrows(
                WorkerUnavailableException.class,
                () -> client.listChunks(worker));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{", "{}", "[null]"})
    void rejectsMissingOrUnreadableInventory(String responseBody) {
        respondWith(withSuccess(
                responseBody, MediaType.APPLICATION_JSON));

        assertThrows(
                WorkerUnavailableException.class,
                () -> client.listChunks(worker));
    }

    @Test
    void timeoutDoesNotBecomeAnEmptyInventory() {
        respondWith(withException(
                new SocketTimeoutException("Simulated read timeout")));

        assertThrows(
                WorkerUnavailableException.class,
                () -> client.listChunks(worker));
    }

    @Test
    void simulatedClientListsHealthyChunksAndRejectsAnUnreachableWorker() {
        InMemoryWorkerClient simulated = new InMemoryWorkerClient();

        assertTrue(simulated.listChunks(worker).isEmpty());

        byte[] data = {1, 2, 3};
        String corruptId = UUID.randomUUID().toString();

        simulated.putChunk(worker, CHUNK_ID, data, CHECKSUM);
        simulated.putChunk(worker, corruptId, data, CHECKSUM);
        simulated.corruptChunk(worker.getWorkerId(), corruptId);

        assertEquals(
                List.of(new ChunkInfo(CHUNK_ID, 3, CHECKSUM)),
                simulated.listChunks(worker));

        simulated.killWorker(worker.getWorkerId());

        assertThrows(
                WorkerUnavailableException.class,
                () -> simulated.listChunks(worker));
    }

    private void respondWith(ResponseCreator response) {
        server.expect(requestTo(INVENTORY_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(
                        HttpHeaders.ACCEPT,
                        MediaType.APPLICATION_JSON_VALUE))
                .andRespond(response);
    }
}