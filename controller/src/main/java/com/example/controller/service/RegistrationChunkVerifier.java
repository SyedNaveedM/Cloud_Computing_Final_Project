package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.controller.client.ChunkNotFoundException;
import com.example.controller.client.CorruptChunkException;
import com.example.controller.client.WorkerClient;
import com.example.controller.client.WorkerUnavailableException;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.Worker;
import org.springframework.stereotype.Component;

@Component
public class RegistrationChunkVerifier {

    private final WorkerClient workerClient;

    public RegistrationChunkVerifier(WorkerClient workerClient) {
        this.workerClient = workerClient;
    }

    public Result verify(Worker worker, Chunk expected) {
        try {
            byte[] data = workerClient.getChunk(
                    worker, expected.getChunkId());

            if (data == null) {
                return Result.UNAVAILABLE;
            }

            if (data.length != expected.getSize()
                    || !ChecksumUtil.sha256(data)
                            .equalsIgnoreCase(expected.getChecksum())) {
                return Result.CORRUPT;
            }

            return Result.VERIFIED;

        } catch (ChunkNotFoundException error) {
            return Result.MISSING;
        } catch (CorruptChunkException error) {
            return Result.CORRUPT;
        } catch (WorkerUnavailableException error) {
            return Result.UNAVAILABLE;
        }
    }

    public enum Result {
        VERIFIED,
        MISSING,
        CORRUPT,
        UNAVAILABLE
    }
}