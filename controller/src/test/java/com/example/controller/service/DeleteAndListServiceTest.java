package com.example.controller.service;

import com.example.controller.client.InMemoryWorkerClient;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.Replica;
import com.example.controller.domain.StoredObject;
import com.example.controller.exception.ObjectNotFoundException;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("fake")
class DeleteAndListServiceTest {

    @Autowired
    private UploadService uploadService;
    @Autowired
    private DeleteService deleteService;
    @Autowired
    private ListService listService;
    @Autowired
    private ChunkRepository chunkRepository;
    @Autowired
    private ReplicaRepository replicaRepository;
    @Autowired
    private InMemoryWorkerClient inMemoryWorkerClient;

    @Test
    void listOnlyReturnsCompleteObjects() {
        StoredObject object = uploadService.upload(new ByteArrayInputStream(randomBytes(10)), "a.bin", "application/octet-stream", null);

        var page = listService.list(0, 50);

        assertTrue(page.items().stream().anyMatch(item -> item.id().equals(object.getId())));
    }

    @Test
    void deleteRemovesDataFromWorkersAndFailsOnSecondDelete() {
        StoredObject object = uploadService.upload(new ByteArrayInputStream(randomBytes(10)), "b.bin", "application/octet-stream", null);
        List<Chunk> chunks = chunkRepository.findByObjectIdOrderByChunkIndex(object.getId());
        List<Replica> replicas = replicaRepository.findByChunkId(chunks.get(0).getChunkId());
        assertTrue(replicas.stream().allMatch(r -> inMemoryWorkerClient.hasChunk(r.getWorkerId(), chunks.get(0).getChunkId())));

        deleteService.deleteObject(object.getId());

        assertTrue(chunkRepository.findByObjectIdOrderByChunkIndex(object.getId()).isEmpty());
        for (Replica replica : replicas) {
            assertFalse(inMemoryWorkerClient.hasChunk(replica.getWorkerId(), chunks.get(0).getChunkId()));
        }
        assertThrows(ObjectNotFoundException.class, () -> deleteService.deleteObject(object.getId()));
    }

    private byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(9).nextBytes(data);
        return data;
    }
}
