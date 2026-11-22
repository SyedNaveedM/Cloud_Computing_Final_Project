package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.controller.client.ChunkNotFoundException;
import com.example.controller.client.CorruptChunkException;
import com.example.controller.client.WorkerClient;
import com.example.controller.client.WorkerUnavailableException;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.Worker;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.service.RegistrationChunkVerifier.Result;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrationChunkVerifierTest {

    private final WorkerClient workerClient = mock(WorkerClient.class);

    private final RegistrationChunkVerifier verifier =
            new RegistrationChunkVerifier(workerClient);

    private final Worker worker = new Worker(
            "worker1",
            "http://localhost:8082",
            null,
            WorkerStatus.DEAD,
            0,
            0);

    private final byte[] original = {0, 1, (byte) 255};

    private final Chunk expected = new Chunk(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            0,
            original.length,
            ChecksumUtil.sha256(original));

    @Test
    void acceptsBytesMatchingControllerMetadata() {
        when(workerClient.getChunk(worker, expected.getChunkId()))
                .thenReturn(original);

        assertEquals(
                Result.VERIFIED, verifier.verify(worker, expected));
    }

    @Test
    void rejectsWrongBytesEvenWhenSizeMatches() {
        byte[] different = {0, 1, 2};

        when(workerClient.getChunk(worker, expected.getChunkId()))
                .thenReturn(different);

        assertEquals(
                Result.CORRUPT, verifier.verify(worker, expected));
    }

    @Test
    void reportsMissingWhenWorkerCannotFindChunk() {
        when(workerClient.getChunk(worker, expected.getChunkId()))
                .thenThrow(new ChunkNotFoundException("Chunk absent"));

        assertEquals(
                Result.MISSING, verifier.verify(worker, expected));
    }

    @Test
    void preservesWorkerCorruptionReport() {
        when(workerClient.getChunk(worker, expected.getChunkId()))
                .thenThrow(new CorruptChunkException("Checksum failed"));

        assertEquals(
                Result.CORRUPT, verifier.verify(worker, expected));
    }

    @Test
    void doesNotTreatTimeoutAsMissing() {
        when(workerClient.getChunk(worker, expected.getChunkId()))
                .thenThrow(new WorkerUnavailableException("Read timed out"));

        assertEquals(
                Result.UNAVAILABLE, verifier.verify(worker, expected));
    }

    @Test
    void cannotVerifyResponseWithoutBody() {
        when(workerClient.getChunk(worker, expected.getChunkId()))
                .thenReturn((byte[]) null);

        assertEquals(
                Result.UNAVAILABLE, verifier.verify(worker, expected));
    }
}