package com.example.controller.client;

import com.example.common.ChecksumUtil;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class WorkerDeletionClientTest {

    private static final String CHUNK_ID =
            "11111111-1111-4111-8111-111111111111";

    private static final String DELETE_URL =
            "http://localhost:8082/internal/chunks/" + CHUNK_ID;

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
                WorkerStatus.ALIVE,
                0L,
                0);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void accepts204AsDeletionAcknowledgment() {
        respondWith(withStatus(HttpStatusCode.valueOf(204)));

        assertDoesNotThrow(
                () -> client.deleteChunkConfirmed(worker, CHUNK_ID));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 302, 404, 500, 503})
    void unexpectedStatusDoesNotConfirmDeletion(int status) {
        respondWith(withStatus(HttpStatusCode.valueOf(status)));

        assertThrows(
                WorkerUnavailableException.class,
                () -> client.deleteChunkConfirmed(worker, CHUNK_ID));
    }

    @Test
    void timeoutDoesNotConfirmDeletion() {
        respondWith(withException(
                new SocketTimeoutException("Simulated DELETE timeout")));

        assertThrows(
                WorkerUnavailableException.class,
                () -> client.deleteChunkConfirmed(worker, CHUNK_ID));
    }

    @Test
    void existingBestEffortMethodStillSwallowsFailure() {
        respondWith(withStatus(HttpStatusCode.valueOf(503)));

        assertDoesNotThrow(
                () -> client.deleteChunk(worker, CHUNK_ID));
    }

    @Test
    void simulatedWorkerKeepsBytesWhileUnreachableAndDeletesAfterRecovery() {
        InMemoryWorkerClient simulated = new InMemoryWorkerClient();
        byte[] data = {10, 20, 30};

        simulated.putChunk(
                worker,
                CHUNK_ID,
                data,
                ChecksumUtil.sha256(data));

        simulated.killWorker(worker.getWorkerId());

        // The confirmed operation must report the failure.
        assertThrows(
                WorkerUnavailableException.class,
                () -> simulated.deleteChunkConfirmed(worker, CHUNK_ID));

        assertTrue(simulated.hasChunk(worker.getWorkerId(), CHUNK_ID));

        // Best-effort deletion hides the error, but cannot remove
        // bytes from an unreachable worker.
        assertDoesNotThrow(
                () -> simulated.deleteChunk(worker, CHUNK_ID));

        assertTrue(simulated.hasChunk(worker.getWorkerId(), CHUNK_ID));

        simulated.reviveWorker(worker.getWorkerId());

        assertArrayEquals(data, simulated.getChunk(worker, CHUNK_ID));

        // A later retry can succeed once the worker is reachable.
        assertDoesNotThrow(
                () -> simulated.deleteChunkConfirmed(worker, CHUNK_ID));

        assertFalse(simulated.hasChunk(worker.getWorkerId(), CHUNK_ID));
    }

    @Test
    void simulatedDeletionRemovesCorruptChunksAndAllowsRepeatedDelete() {
        InMemoryWorkerClient simulated = new InMemoryWorkerClient();
        byte[] data = {1, 2, 3};

        simulated.putChunk(
                worker,
                CHUNK_ID,
                data,
                ChecksumUtil.sha256(data));

        simulated.corruptChunk(worker.getWorkerId(), CHUNK_ID);

        assertThrows(
                CorruptChunkException.class,
                () -> simulated.getChunk(worker, CHUNK_ID));

        // Corruption must not prevent deleting the copy.
        assertDoesNotThrow(
                () -> simulated.deleteChunkConfirmed(worker, CHUNK_ID));

        assertFalse(simulated.hasChunk(worker.getWorkerId(), CHUNK_ID));

        // Retrying DELETE after the chunk is already absent is safe.
        assertDoesNotThrow(
                () -> simulated.deleteChunkConfirmed(worker, CHUNK_ID));
    }
    @Test
void simulatedDeletedChunkCannotReturnAfterRevival() {
    InMemoryWorkerClient simulated = new InMemoryWorkerClient();
    byte[] data = {1, 2, 3};
    String checksum = ChecksumUtil.sha256(data);

    simulated.putChunk(worker, CHUNK_ID, data, checksum);
    simulated.deleteChunkConfirmed(worker, CHUNK_ID);

    simulated.killWorker(worker.getWorkerId());
    simulated.reviveWorker(worker.getWorkerId());

    assertThrows(
            ChunkRejectedException.class,
            () -> simulated.putChunk(worker, CHUNK_ID, data, checksum));

    assertFalse(simulated.hasChunk(worker.getWorkerId(), CHUNK_ID));

    assertThrows(
            ChunkNotFoundException.class,
            () -> simulated.getChunk(worker, CHUNK_ID));

    assertTrue(simulated.listChunks(worker).isEmpty());

    // Repeated deletion must preserve the marker.
    simulated.deleteChunkConfirmed(worker, CHUNK_ID);

    assertThrows(
            ChunkRejectedException.class,
            () -> simulated.putChunk(worker, CHUNK_ID, data, checksum));
}

@Test
void simulatedDeleteBeforeFirstPutRetiresIdOnlyOnThatWorker() {
    InMemoryWorkerClient simulated = new InMemoryWorkerClient();
    byte[] data = {4, 5, 6};
    String checksum = ChecksumUtil.sha256(data);

    // No chunk has been uploaded yet.
    simulated.deleteChunkConfirmed(worker, CHUNK_ID);

    assertThrows(
            ChunkRejectedException.class,
            () -> simulated.putChunk(worker, CHUNK_ID, data, checksum));

    Worker otherWorker = new Worker(
            "worker2",
            "http://localhost:8083",
            Instant.now(),
            WorkerStatus.ALIVE,
            0L,
            0);

    // Deletion on worker1 must not create a marker on worker2.
    assertDoesNotThrow(
            () -> simulated.putChunk(
                    otherWorker, CHUNK_ID, data, checksum));

    assertArrayEquals(
            data,
            simulated.getChunk(otherWorker, CHUNK_ID));

    assertFalse(simulated.hasChunk(worker.getWorkerId(), CHUNK_ID));
}

@Test
void unreachableSimulatedWorkerDoesNotRecordDeletion() {
    InMemoryWorkerClient simulated = new InMemoryWorkerClient();
    byte[] data = {7, 8, 9};
    String checksum = ChecksumUtil.sha256(data);

    simulated.killWorker(worker.getWorkerId());

    assertThrows(
            WorkerUnavailableException.class,
            () -> simulated.deleteChunkConfirmed(worker, CHUNK_ID));

    assertDoesNotThrow(
            () -> simulated.deleteChunk(worker, CHUNK_ID));

    simulated.reviveWorker(worker.getWorkerId());

    // Neither failed request reached the worker, so no marker exists.
    assertDoesNotThrow(
            () -> simulated.putChunk(worker, CHUNK_ID, data, checksum));

    assertArrayEquals(data, simulated.getChunk(worker, CHUNK_ID));
}

@ParameterizedTest
@ValueSource(ints = {400, 409})
void httpPutRejectionIsReportedAsChunkRejected(int status) {
    server.expect(requestTo(
                    worker.getUrl() + "/internal/chunks/" + CHUNK_ID))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)));

    byte[] data = {1, 2, 3};

    assertThrows(
            ChunkRejectedException.class,
            () -> client.putChunk(
                    worker,
                    CHUNK_ID,
                    data,
                    ChecksumUtil.sha256(data)));
}
    private void respondWith(ResponseCreator response) {
        server.expect(requestTo(DELETE_URL))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(response);
    }
}