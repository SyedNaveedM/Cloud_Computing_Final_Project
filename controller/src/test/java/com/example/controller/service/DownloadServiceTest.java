package com.example.controller.service;

import com.example.common.ChecksumUtil;
import com.example.controller.client.InMemoryWorkerClient;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.Replica;
import com.example.controller.domain.ReplicaState;
import com.example.controller.domain.StoredObject;
import com.example.controller.exception.ChunkUnavailableException;
import com.example.controller.exception.ObjectUnavailableException;
import com.example.controller.repository.ChunkRepository;
import com.example.controller.repository.ReplicaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("fake")
class DownloadServiceTest {

    @Autowired
    private UploadService uploadService;
    @Autowired
    private DownloadService downloadService;
    @Autowired
    private InMemoryWorkerClient inMemoryWorkerClient;
    @Autowired
    private ChunkRepository chunkRepository;
    @Autowired
    private ReplicaRepository replicaRepository;

    @Test
    void roundTripPreservesBytesAndChecksum() throws IOException {
        byte[] data = randomBytes(500_000);
        StoredObject object = uploadService.upload(new ByteArrayInputStream(data), "f.bin", "application/octet-stream", null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        downloadService.preflightCheck(object.getId());
        downloadService.streamObject(object.getId(), out);

        assertEquals(ChecksumUtil.sha256(data), ChecksumUtil.sha256(out.toByteArray()));
        assertEquals(object.getChecksum(), ChecksumUtil.sha256(out.toByteArray()));
    }

    @Test
    void downloadSurvivesOneKilledWorker() throws IOException {
        byte[] data = randomBytes(1000);
        StoredObject object = uploadService.upload(new ByteArrayInputStream(data), "f.bin", "application/octet-stream", null);

        Chunk chunk = chunkRepository.findByObjectIdOrderByChunkIndex(object.getId()).get(0);
        List<Replica> replicas = replicaRepository.findByChunkId(chunk.getChunkId());
        inMemoryWorkerClient.killWorker(replicas.get(0).getWorkerId());

        try {
            byte[] fetched = downloadService.fetchVerified(chunk);
            assertEquals(ChecksumUtil.sha256(data), ChecksumUtil.sha256(fetched));
        } finally {
            inMemoryWorkerClient.reviveWorker(replicas.get(0).getWorkerId());
        }
    }

    @Test
    void corruptedReplicaIsSkippedAndFlaggedWithoutFailingTheDownload() throws IOException {
        byte[] data = randomBytes(1000);
        StoredObject object = uploadService.upload(new ByteArrayInputStream(data), "f.bin", "application/octet-stream", null);

        Chunk chunk = chunkRepository.findByObjectIdOrderByChunkIndex(object.getId()).get(0);
        List<Replica> replicas = replicaRepository.findByChunkId(chunk.getChunkId());
        Replica corrupted = replicas.get(0);
        inMemoryWorkerClient.corruptChunk(corrupted.getWorkerId(), chunk.getChunkId());

        // fetchVerified shuffles replica order, so the corrupted one isn't guaranteed to be
        // tried on the very first call; repeat until it has been (or fail after a generous cap).
        Replica reloaded = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            byte[] fetched = downloadService.fetchVerified(chunk);
            assertEquals(ChecksumUtil.sha256(data), ChecksumUtil.sha256(fetched));
            reloaded = replicaRepository.findById(corrupted.getId()).orElseThrow();
            if (reloaded.getState() == ReplicaState.CORRUPT) {
                break;
            }
        }
        assertEquals(ReplicaState.CORRUPT, reloaded.getState());
    }

    @Test
    void fetchVerifiedAndPreflightFailWhenNoHealthyReplicaExists() throws IOException {
        byte[] data = randomBytes(1000);
        StoredObject object = uploadService.upload(new ByteArrayInputStream(data), "f.bin", "application/octet-stream", null);

        Chunk chunk = chunkRepository.findByObjectIdOrderByChunkIndex(object.getId()).get(0);
        List<Replica> replicas = replicaRepository.findByChunkId(chunk.getChunkId());
        for (Replica replica : replicas) {
            inMemoryWorkerClient.corruptChunk(replica.getWorkerId(), chunk.getChunkId());
            replica.setState(ReplicaState.CORRUPT);
            replicaRepository.save(replica);
        }

        assertThrows(ChunkUnavailableException.class, () -> downloadService.fetchVerified(chunk));
        assertThrows(ObjectUnavailableException.class, () -> downloadService.preflightCheck(object.getId()));
    }

    private byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(7).nextBytes(data);
        return data;
    }
}
