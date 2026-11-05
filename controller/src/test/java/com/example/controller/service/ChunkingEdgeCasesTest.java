package com.example.controller.service;

import com.example.common.Constants;
import com.example.controller.domain.Chunk;
import com.example.controller.domain.StoredObject;
import com.example.controller.repository.ChunkRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("fake")
class ChunkingEdgeCasesTest {

    @Autowired
    private UploadService uploadService;
    @Autowired
    private ChunkRepository chunkRepository;

    @ParameterizedTest(name = "{0} bytes -> {1} chunk(s)")
    @CsvSource({
            "1, 1",
            "1048576, 1",            // exactly 1 MB
            "1048577, 2",            // 1 MB + 1 byte
            "5767168, 6"             // 5.5 MB -> 6 chunks, last one smaller
    })
    void splitsIntoExpectedChunkCount(int fileSize, int expectedChunkCount) {
        byte[] data = randomBytes(fileSize);

        StoredObject object = uploadService.upload(new ByteArrayInputStream(data), "f.bin", "application/octet-stream", null);

        assertEquals(expectedChunkCount, object.getChunkCount());
        assertEquals(fileSize, object.getSize());

        List<Chunk> chunks = chunkRepository.findByObjectIdOrderByChunkIndex(object.getId());
        assertEquals(expectedChunkCount, chunks.size());

        long remaining = fileSize;
        for (Chunk chunk : chunks) {
            long expectedSize = Math.min(remaining, Constants.CHUNK_SIZE);
            assertEquals(expectedSize, chunk.getSize());
            remaining -= expectedSize;
        }
        assertEquals(0, remaining);
    }

    private byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(123).nextBytes(data);
        return data;
    }
}
