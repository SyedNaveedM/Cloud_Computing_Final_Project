package com.example.controller.service;

import com.example.common.Constants;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.ObjectStatus;
import com.example.controller.domain.Replica;
import com.example.controller.domain.ReplicaState;
import com.example.controller.domain.StoredObject;
import com.example.controller.domain.WorkerStatus;
import com.example.controller.exception.InsufficientWorkersException;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import com.example.controller.repository.StoredObjectRepository;
import com.example.controller.repository.WorkerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("fake")
class UploadServiceTest {

    @Autowired
    private UploadService uploadService;
    @Autowired
    private StoredObjectRepository objectRepository;
    @Autowired
    private ChunkRepository chunkRepository;
    @Autowired
    private ReplicaRepository replicaRepository;
    @Autowired
    private WorkerRepository workerRepository;

    @Test
    void multiChunkUploadCreatesTwoReplicasPerChunkOnDifferentWorkers() {
        byte[] data = randomBytes(Constants.CHUNK_SIZE + 100);

        StoredObject object = uploadService.upload(new ByteArrayInputStream(data), "file.bin", "application/octet-stream", null);

        assertEquals(ObjectStatus.COMPLETE, object.getStatus());
        assertEquals(data.length, object.getSize());
        assertEquals(2, object.getChunkCount());

        List<Chunk> chunks = chunkRepository.findByObjectIdOrderByChunkIndex(object.getId());
        assertEquals(2, chunks.size());
        assertEquals(Constants.CHUNK_SIZE, chunks.get(0).getSize());
        assertEquals(100, chunks.get(1).getSize());

        for (Chunk chunk : chunks) {
            List<Replica> replicas = replicaRepository.findByChunkId(chunk.getChunkId());
            assertEquals(2, replicas.size());
            assertNotEquals(replicas.get(0).getWorkerId(), replicas.get(1).getWorkerId());
            assertTrue(replicas.stream().allMatch(r -> r.getState() == ReplicaState.OK));
        }
    }

    @Test
    void uploadFailsFastWhenFewerThanTwoWorkersAreAlive() {
        workerRepository.findAll().forEach(worker -> {
            worker.setStatus(WorkerStatus.DEAD);
            workerRepository.save(worker);
        });
        try {
            byte[] data = randomBytes(10);
            assertThrows(InsufficientWorkersException.class,
                    () -> uploadService.upload(new ByteArrayInputStream(data), "f.bin", "application/octet-stream", null));
        } finally {
            workerRepository.findAll().forEach(worker -> {
                worker.setStatus(WorkerStatus.ALIVE);
                workerRepository.save(worker);
            });
        }
    }

    private byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }
}
